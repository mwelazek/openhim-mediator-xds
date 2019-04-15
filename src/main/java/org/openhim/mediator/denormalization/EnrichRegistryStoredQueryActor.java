/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.denormalization;

import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.apache.commons.io.IOUtils;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.messages.EnrichRegistryStoredQuery;
import org.openhim.mediator.messages.EnrichRegistryStoredQueryResponse;
import org.openhim.mediator.normalization.ParseRegistryStoredQueryActor;

import javax.xml.namespace.QName;
import javax.xml.stream.*;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.io.StringWriter;

/**
 * Enriches registry stored query messages.
 * <br/><br/>
 * Messages supported:
 * <ul>
 * <li>EnrichRegistryStoredQuery - responds with EnrichRegistryStoreQueryResponse</li>
 * </ul>
 */
public class EnrichRegistryStoredQueryActor extends UntypedActor {

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private MediatorConfig config;

    public EnrichRegistryStoredQueryActor(MediatorConfig config) {
        this.config = config;
    }

    private String enrichStoredQueryXML(Identifier id, InputStream xml) throws XMLStreamException {
        XMLEventReader reader = XMLInputFactory.newInstance().createXMLEventReader(xml);
        StringWriter output = new StringWriter();
        XMLEventWriter writer = XMLOutputFactory.newInstance().createXMLEventWriter(output);
        XMLEventFactory eventFactory = XMLEventFactory.newFactory();

        String curSlot = null;
        boolean patientIdSlot = false;
        boolean xdsAddressElementActive = false;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.getEventType() == XMLEvent.START_ELEMENT) {
                StartElement elem = event.asStartElement();
                if ("To".equals(elem.getName().getLocalPart())) {
                    writer.add(event);
                    xdsAddressElementActive = true;
                }
            } else if (event.getEventType() == XMLEvent.END_ELEMENT) {
                EndElement elem = event.asEndElement();
                if ("To".equals(elem.getName().getLocalPart())) {
                    XMLEvent xdsRegistryAddressEvent = eventFactory.createCharacters("http://10.255.100.45:8010/axis2/services/xdsregistryb");
                    //XMLEvent xdsRegistryAddressEvent = eventFactory.createCharacters(buildRegistryPath());
                    writer.add(xdsRegistryAddressEvent);
                    xdsAddressElementActive = false;
                }
            }

            if (event.getEventType() == XMLEvent.START_ELEMENT) {
                StartElement elem = event.asStartElement();
                if ("Slot".equals(elem.getName().getLocalPart())) {
                    curSlot = elem.getAttributeByName(new QName("name")).getValue();
                } else if ("Value".equals(elem.getName().getLocalPart()) &&
                        ParseRegistryStoredQueryActor.PATIENT_ID_SLOT_TYPE.equals(curSlot)) {
                    patientIdSlot = true;
                    writer.add(event);
                }
            } else if (event.getEventType() == XMLEvent.END_ELEMENT) {
                EndElement elem = event.asEndElement();
                if (patientIdSlot && "Value".equals(elem.getName().getLocalPart())) {
                    XMLEvent ecidEvent = eventFactory.createCharacters("'" + id.toString() + "'");
                    writer.add(ecidEvent);
                    patientIdSlot = false;
                }
            }

            if (!patientIdSlot && !xdsAddressElementActive) {
                writer.add(event);
            }
        }

        writer.close();
        return output.toString();
    }

    private void enrichMessage(EnrichRegistryStoredQuery msg) {
        try {
            String enrichedMessage = enrichStoredQueryXML(msg.getPatientID(), IOUtils.toInputStream(msg.getOriginalRequest()));
            EnrichRegistryStoredQueryResponse response = new EnrichRegistryStoredQueryResponse(msg, enrichedMessage);
            msg.getRespondTo().tell(response, getSelf());
        } catch (XMLStreamException ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private String buildRegistryPath() {
        return String.format(
                "http://%s:%s/%s", config.getProperty("xds.registry.host"), 
                config.getProperty("xds.b.registry.port"),
                config.getProperty("xds.registry.path")
        );
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof EnrichRegistryStoredQuery) {
            enrichMessage((EnrichRegistryStoredQuery) msg);
        } else {
            unhandled(msg);
        }
    }
}
