/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.parser.stmt.rfc6020.effective;

import org.opendaylight.yangtools.yang.model.api.stmt.RequireInstanceStatement;
import org.opendaylight.yangtools.yang.parser.spi.meta.StmtContext;

public final class RequireInstanceEffectiveStatementImpl extends DeclaredEffectiveStatementBase<Boolean,
        RequireInstanceStatement> {
    public RequireInstanceEffectiveStatementImpl(final StmtContext<Boolean, RequireInstanceStatement, ?> ctx) {
        super(ctx);
    }
}