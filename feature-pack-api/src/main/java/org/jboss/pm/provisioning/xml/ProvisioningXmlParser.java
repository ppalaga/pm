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

package org.jboss.pm.provisioning.xml;

import java.io.InputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jboss.pm.provisioning.ProvisioningMetaData;
import org.jboss.staxmapper.XMLMapper;

/**
 *
 * @author Alexey Loubyansky
 */
public class ProvisioningXmlParser {

    private static final QName ROOT_1_0 = new QName(ProvisioningXmlParser10.NAMESPACE_1_0, ProvisioningXmlParser10.Element.INSTALLATION.getLocalName());

    private static final XMLInputFactory INPUT_FACTORY = XMLInputFactory.newInstance();
    private final XMLMapper mapper;

    public ProvisioningXmlParser() {
        mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(ROOT_1_0, new ProvisioningXmlParser10());
    }

    public ProvisioningMetaData parse(final InputStream input) throws XMLStreamException {

        final XMLInputFactory inputFactory = INPUT_FACTORY;
        setIfSupported(inputFactory, XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
        setIfSupported(inputFactory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        final XMLStreamReader streamReader = inputFactory.createXMLStreamReader(input);
        final ProvisioningMetaData metadata = new ProvisioningMetaData();
        mapper.parseDocument(metadata, streamReader);
        return metadata;
    }

    private void setIfSupported(final XMLInputFactory inputFactory, final String property, final Object value) {
        if (inputFactory.isPropertySupported(property)) {
            inputFactory.setProperty(property, value);
        }
    }
}
