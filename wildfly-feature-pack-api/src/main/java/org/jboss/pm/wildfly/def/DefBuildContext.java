/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.pm.wildfly.def;

import java.io.File;

import org.jboss.pm.def.PackageDef.PackageDefBuilder;

/**
 *
 * @author Alexey Loubyansky
 */
class DefBuildContext {

    private final File homeDir;
    private final File modulesDir;

    WFFeaturePackDefBuilder fpBuilder;
    PackageDefBuilder pkgBuilder;

    DefBuildContext(File homeDir, String modulesDir) {
        assert homeDir != null : "Home directory is missing";
        assert homeDir != null : "Modules directory is missing";
        this.homeDir = homeDir;
        this.modulesDir = new File(homeDir, modulesDir);
    }

    File getHomeDir() {
        return homeDir;
    }

    File getModulesDir() {
        return modulesDir;
    }
}
