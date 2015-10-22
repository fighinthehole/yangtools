/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.data.impl.schema.tree;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import java.util.Collection;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNodeContainer;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ConflictingModificationAppliedException;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataValidationFailedException;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ModificationType;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ModifiedNodeDoesNotExistException;
import org.opendaylight.yangtools.yang.data.api.schema.tree.TreeType;
import org.opendaylight.yangtools.yang.data.api.schema.tree.spi.MutableTreeNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.spi.TreeNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.spi.TreeNodeFactory;
import org.opendaylight.yangtools.yang.data.api.schema.tree.spi.Version;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.api.NormalizedNodeContainerBuilder;

abstract class AbstractNodeContainerModificationStrategy extends SchemaAwareApplyOperation {

    private final Class<? extends NormalizedNode<?, ?>> nodeClass;
    private final boolean verifyChildrenStructure;

    protected AbstractNodeContainerModificationStrategy(final Class<? extends NormalizedNode<?, ?>> nodeClass,
            final TreeType treeType) {
        this.nodeClass = Preconditions.checkNotNull(nodeClass , "nodeClass");
        this.verifyChildrenStructure = (treeType == TreeType.CONFIGURATION);
    }

    @SuppressWarnings("rawtypes")
    @Override
    void verifyStructure(final NormalizedNode<?, ?> writtenValue, final boolean verifyChildren) {
        checkArgument(nodeClass.isInstance(writtenValue), "Node %s is not of type %s", writtenValue, nodeClass);
        checkArgument(writtenValue instanceof NormalizedNodeContainer);
        if (verifyChildrenStructure && verifyChildren) {
            final NormalizedNodeContainer container = (NormalizedNodeContainer) writtenValue;
            for (final Object child : container.getValue()) {
                checkArgument(child instanceof NormalizedNode);
                final NormalizedNode<?, ?> castedChild = (NormalizedNode<?, ?>) child;
                final Optional<ModificationApplyOperation> childOp = getChild(castedChild.getIdentifier());
                if (childOp.isPresent()) {
                    childOp.get().verifyStructure(castedChild, verifyChildren);
                } else {
                    throw new SchemaValidationFailedException(String.format(
                            "Child %s is not valid child according to schema.", castedChild.getIdentifier()));
                }
            }
        }
    }

    @Override
    protected TreeNode applyWrite(final ModifiedNode modification,
            final Optional<TreeNode> currentMeta, final Version version) {
        final NormalizedNode<?, ?> newValue = modification.getWrittenValue();
        final TreeNode newValueMeta = TreeNodeFactory.createTreeNode(newValue, version);

        if (modification.getChildren().isEmpty()) {
            return newValueMeta;
        }

        /*
         * This is where things get interesting. The user has performed a write and
         * then she applied some more modifications to it. So we need to make sense
         * of that an apply the operations on top of the written value. We could have
         * done it during the write, but this operation is potentially expensive, so
         * we have left it out of the fast path.
         *
         * As it turns out, once we materialize the written data, we can share the
         * code path with the subtree change. So let's create an unsealed TreeNode
         * and run the common parts on it -- which end with the node being sealed.
         *
         * FIXME: this code needs to be moved out from the prepare() path and into
         *        the read() and seal() paths. Merging of writes needs to be charged
         *        to the code which originated this, not to the code which is
         *        attempting to make it visible.
         */
        final MutableTreeNode mutable = newValueMeta.mutable();
        mutable.setSubtreeVersion(version);

        @SuppressWarnings("rawtypes")
        final NormalizedNodeContainerBuilder dataBuilder = createBuilder(newValue);
        final TreeNode result = mutateChildren(mutable, dataBuilder, version, modification.getChildren());

        // We are good to go except one detail: this is a single logical write, but
        // we have a result TreeNode which has been forced to materialized, e.g. it
        // is larger than it needs to be. Create a new TreeNode to host the data.
        return TreeNodeFactory.createTreeNode(result.getData(), version);
    }

    /**
     * Applies write/remove diff operation for each modification child in modification subtree.
     * Operation also sets the Data tree references for each Tree Node (Index Node) in meta (MutableTreeNode) structure.
     *
     * @param meta MutableTreeNode (IndexTreeNode)
     * @param data DataBuilder
     * @param nodeVersion Version of TreeNode
     * @param modifications modification operations to apply
     * @return Sealed immutable copy of TreeNode structure with all Data Node references set.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private TreeNode mutateChildren(final MutableTreeNode meta, final NormalizedNodeContainerBuilder data,
            final Version nodeVersion, final Iterable<ModifiedNode> modifications) {

        for (final ModifiedNode mod : modifications) {
            final YangInstanceIdentifier.PathArgument id = mod.getIdentifier();
            final Optional<TreeNode> cm = meta.getChild(id);

            final Optional<TreeNode> result = resolveChildOperation(id).apply(mod, cm, nodeVersion);
            if (result.isPresent()) {
                final TreeNode tn = result.get();
                meta.addChild(tn);
                data.addChild(tn.getData());
            } else {
                meta.removeChild(id);
                data.removeChild(id);
            }
        }

        meta.setData(data.build());
        return meta.seal();
    }

    @Override
    protected TreeNode applyMerge(final ModifiedNode modification, final TreeNode currentMeta,
            final Version version) {
        // For Node Containers - merge is same as subtree change - we only replace children.
        return applyTouch(modification, currentMeta, version);
    }

    @Override
    protected TreeNode applyTouch(final ModifiedNode modification, final TreeNode currentMeta, final Version version) {
        /*
         * The user may have issued an empty merge operation. In this case we do not perform
         * a data tree mutation, do not pass GO, and do not collect useless garbage. It
         * also means the ModificationType is UNMODIFIED.
         */
        final Collection<ModifiedNode> children = modification.getChildren();
        if (!children.isEmpty()) {
            @SuppressWarnings("rawtypes")
            final NormalizedNodeContainerBuilder dataBuilder = createBuilder(currentMeta.getData());
            final MutableTreeNode newMeta = currentMeta.mutable();
            newMeta.setSubtreeVersion(version);
            final TreeNode ret = mutateChildren(newMeta, dataBuilder, version, children);

            /*
             * It is possible that the only modifications under this node were empty merges,
             * which were turned into UNMODIFIED. If that is the case, we can turn this operation
             * into UNMODIFIED, too, potentially cascading it up to root. This has the benefit
             * of speeding up any users, who can skip processing child nodes.
             *
             * In order to do that, though, we have to check all child operations are UNMODIFIED.
             * Let's do precisely that, stopping as soon we find a different result.
             */
            for (final ModifiedNode child : children) {
                if (child.getModificationType() != ModificationType.UNMODIFIED) {
                    modification.resolveModificationType(ModificationType.SUBTREE_MODIFIED);
                    return ret;
                }
            }
        }

        // The merge operation did not have any children, or all of them turned out to be UNMODIFIED, hence do not
        // replace the metadata node.
        modification.resolveModificationType(ModificationType.UNMODIFIED);
        return currentMeta;
    }

    @Override
    protected void checkTouchApplicable(final YangInstanceIdentifier path, final NodeModification modification,
            final Optional<TreeNode> current) throws DataValidationFailedException {
        if (!modification.getOriginal().isPresent() && !current.isPresent()) {
            throw new ModifiedNodeDoesNotExistException(path, String.format("Node %s does not exist. Cannot apply modification to its children.", path));
        }

        if (!current.isPresent()) {
            throw new ConflictingModificationAppliedException(path, "Node was deleted by other transaction.");
        }

        checkChildPreconditions(path, modification, current.get());
    }

    /**
     * Recursively check child preconditions.
     *
     * @param path current node path
     * @param modification current modification
     * @param current Current data tree node.
     */
    private void checkChildPreconditions(final YangInstanceIdentifier path, final NodeModification modification, final TreeNode current) throws DataValidationFailedException {
        for (final NodeModification childMod : modification.getChildren()) {
            final YangInstanceIdentifier.PathArgument childId = childMod.getIdentifier();
            final Optional<TreeNode> childMeta = current.getChild(childId);

            final YangInstanceIdentifier childPath = path.node(childId);
            resolveChildOperation(childId).checkApplicable(childPath, childMod, childMeta);
        }
    }

    @Override
    protected void checkMergeApplicable(final YangInstanceIdentifier path, final NodeModification modification,
            final Optional<TreeNode> current) throws DataValidationFailedException {
        if (current.isPresent()) {
            checkChildPreconditions(path, modification, current.get());
        }
    }

    protected boolean verifyChildrenStructure() {
        return verifyChildrenStructure;
    }

    @SuppressWarnings("rawtypes")
    protected abstract NormalizedNodeContainerBuilder createBuilder(NormalizedNode<?, ?> original);
}
