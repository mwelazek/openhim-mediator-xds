package org.openhim.mediator.orchestration;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import ihe.iti.xds_b._2007.ObjectFactory;
import oasis.names.tc.ebxml_regrep.xsd.rim._3.*;
import org.apache.http.HttpStatus;
import org.dcm4chee.xds2.common.XDSConstants;
import org.dcm4chee.xds2.infoset.util.InfosetUtil;
import org.openhim.mediator.Util;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.denormalization.ResolveEnterpriseIdentifierActor;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorResponse;
import org.openhim.mediator.exceptions.CXParseException;
import org.openhim.mediator.exceptions.ValidationException;
import org.openhim.mediator.messages.*;
import org.openhim.mediator.normalization.ParseProvideAndRegisterRequestActor;

import javax.xml.bind.JAXBException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An orchestrator for enriching XDS.b Provide and Register Document Set requests.
 * <br/><br/>
 * Messages supported:
 * <ul>
 *     <li>EnrichPnRRequestMessage: responds with EnrichPnRRequestMessageResponse</li>
 * </ul>
 */
public class ProvideAndRegisterOrchestrationActor extends UntypedActor {

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private abstract class IdentifierMapping {
        boolean resolved = false;
        Identifier fromId;

        abstract void resolve(Identifier resolvedId);
    }

    private class PatientIdentifierMapping extends IdentifierMapping {
        String documentNodeURN;
        RegistryObjectType documentNode;

        public PatientIdentifierMapping(Identifier fromId, String documentNodeURN, RegistryObjectType documentNode) {
            this.documentNodeURN = documentNodeURN;
            this.documentNode = documentNode;
            this.fromId = fromId;
        }

        @Override
        void resolve(Identifier resolvedId) {
            InfosetUtil.setExternalIdentifierValue(documentNodeURN, resolvedId.toString(), documentNode);
            resolved = true;
        }
    }

    private class HealthcareWorkerIdentifierMapping extends IdentifierMapping {
        List<String> slotList;

        public HealthcareWorkerIdentifierMapping(Identifier fromId, List<String> slotList) {
            this.fromId = fromId;
            this.slotList = slotList;
        }

        @Override
        void resolve(Identifier resolvedId) {
            String newPersonXCN = resolvedId.toXCN();
            slotList.clear();
            slotList.add(newPersonXCN);
            resolved = true;
        }
    }

    private class FacilityIdentifierMapping extends IdentifierMapping {
        String localLocationName;
        List<String> slotList;

        public FacilityIdentifierMapping(Identifier fromId, String localLocationName, List<String> slotList) {
            this.fromId = fromId;
            this.localLocationName = localLocationName;
            this.slotList = slotList;
        }

        @Override
        void resolve(Identifier resolvedId) {
            String newInstitutionXON = resolvedId.toXON(localLocationName);
            slotList.clear();
            slotList.add(newInstitutionXON);
            resolved = true;
        }
    }

    private final MediatorConfig config;
    private final ActorRef resolvePatientIdHandler;
    private final ActorRef resolveHealthcareWorkerIdHandler;
    private final ActorRef resolveFacilityIdHandler;
    private OrchestrateProvideAndRegisterRequest originalRequest;

    private String request;
    private ProvideAndRegisterDocumentSetRequestType parsedRequest;
    private List<IdentifierMapping> enterprisePatientIds = new ArrayList<>();
    private List<IdentifierMapping> enterpriseHealthcareWorkerIds = new ArrayList<>();
    private List<IdentifierMapping> enterpriseFacilityIds = new ArrayList<>();


    public ProvideAndRegisterOrchestrationActor(MediatorConfig config, ActorRef resolvePatientIdHandler, ActorRef resolveHealthcareWorkerIdHandler, ActorRef resolveFacilityIdHandler) {
        this.config = config;
        this.resolvePatientIdHandler = resolvePatientIdHandler;
        this.resolveHealthcareWorkerIdHandler = resolveHealthcareWorkerIdHandler;
        this.resolveFacilityIdHandler = resolveFacilityIdHandler;
    }

    public ProvideAndRegisterOrchestrationActor(MediatorConfig config) {
        this.config = config;
        this.resolvePatientIdHandler = getContext().actorOf(Props.create(ResolveEnterpriseIdentifierActor.class, config));
        this.resolveHealthcareWorkerIdHandler = null;
        this.resolveFacilityIdHandler = null;
    }

    private void parseRequest(OrchestrateProvideAndRegisterRequest msg) {
        log.info("Parsing Xds.b Provide and Register request");
        request = msg.getRequestObject();
        ActorRef parseHandler = getContext().actorOf(Props.create(ParseProvideAndRegisterRequestActor.class));
        parseHandler.tell(new SimpleMediatorRequest<>(msg.getRequestHandler(), getSelf(), request), getSelf());
    }

    private void processParsedRequest(ProvideAndRegisterDocumentSetRequestType doc) {
        log.info("Request parsed. Processing document");
        parsedRequest = doc;
        try {
            initIdentifiersToBeResolvedMappings();
            if (!checkAndRespondIfAllResolved()) {
                resolveEnterpriseIdentifiers();
            }
        } catch (ValidationException ex) {
            FinishRequest fr = new FinishRequest(ex.getMessage(), "text/plain", HttpStatus.SC_BAD_REQUEST);
            originalRequest.getRequestHandler().tell(fr, getSelf());
        }
    }

    private void initIdentifiersToBeResolvedMappings() throws ValidationException {
        readPatientIdentifiers();
        readHealthcareWorkerAndFacilityIdentifiers();
    }

    private void readPatientIdentifiers() throws CXParseException {
        RegistryPackageType regPac = InfosetUtil.getRegistryPackage(parsedRequest.getSubmitObjectsRequest(), XDSConstants.UUID_XDSSubmissionSet);
        String CX = InfosetUtil.getExternalIdentifierValue(XDSConstants.UUID_XDSSubmissionSet_patientId, regPac);
        enterprisePatientIds.add(new PatientIdentifierMapping(new Identifier(CX), XDSConstants.UUID_XDSSubmissionSet_patientId, regPac));

        List<ExtrinsicObjectType> eos = InfosetUtil.getExtrinsicObjects(parsedRequest.getSubmitObjectsRequest());
        for (ExtrinsicObjectType eo : eos) {
            String documentPatCX = InfosetUtil.getExternalIdentifierValue(XDSConstants.UUID_XDSDocumentEntry_patientId, eo);
            enterprisePatientIds.add(new PatientIdentifierMapping(new Identifier(documentPatCX), XDSConstants.UUID_XDSDocumentEntry_patientId, eo));
        }
    }

    protected void readHealthcareWorkerAndFacilityIdentifiers() throws ValidationException {
        List<ExtrinsicObjectType> eos = InfosetUtil.getExtrinsicObjects(parsedRequest.getSubmitObjectsRequest());
        for (ExtrinsicObjectType eo : eos) {
            List<Map<String, SlotType1>> authorClassSlots = null;
            try {
                authorClassSlots = this.getClassificationSlotsFromExtrinsicObject(XDSConstants.UUID_XDSDocumentEntry_author, eo);
            } catch (JAXBException e) {
                throw new ValidationException(e);
            }

            for (Map<String, SlotType1> slotMap : authorClassSlots) {

                String localProviderID = null;
                String localProviderIDAssigningAuthority = null;
                String localLocationID = null;
                String localLocationIDAssigningAuthority = null;
                String localLocationName = null;
                List<String> personSlotValList = null;
                List<String> institutionSlotValList = null;

                if (slotMap.containsKey(XDSConstants.SLOT_NAME_AUTHOR_PERSON)) {
                    SlotType1 personSlot = slotMap.get(XDSConstants.SLOT_NAME_AUTHOR_PERSON);
                    personSlotValList = personSlot.getValueList().getValue();

                    // loop through all values and find the first one with an ID and assigning authority
                    for (String val : personSlotValList) {
                        String[] xcnComponents = val.split("\\^", -1);

                        // if the identifier component exists
                        if (!xcnComponents[0].isEmpty() && !xcnComponents[8].isEmpty()) {
                            localProviderID = xcnComponents[0];
                            localProviderIDAssigningAuthority = xcnComponents[8].substring(xcnComponents[8].indexOf('&') + 1, xcnComponents[8].lastIndexOf('&'));
                            break;
                        }
                    }
                }

                if (slotMap.containsKey(XDSConstants.SLOT_NAME_AUTHOR_INSTITUTION)) {
                    SlotType1 institutionSlot = slotMap.get(XDSConstants.SLOT_NAME_AUTHOR_INSTITUTION);
                    institutionSlotValList = institutionSlot.getValueList().getValue();

                    // loop through all values and find the first one with an ID
                    for (String val : institutionSlotValList) {
                        String[] xonComponents = val.split("\\^", -1);

                        // if the identifier component exists
                        if (xonComponents.length >= 10 && !xonComponents[5].isEmpty() && !xonComponents[9].isEmpty()) {
                            localLocationID = xonComponents[9];
                            localLocationName = xonComponents[0];
                            localLocationIDAssigningAuthority = xonComponents[5].substring(xonComponents[5].indexOf('&') + 1, xonComponents[5].lastIndexOf('&'));
                        }
                    }

                }

                if (localProviderID == null && localLocationID == null) {
                    throw new ValidationException("EPID and ELID could not be extracted from the CDS metadata");
                }

                if (localProviderID!=null) {
                    Identifier id = new Identifier(localProviderID, new AssigningAuthority("", localProviderIDAssigningAuthority));
                    enterpriseHealthcareWorkerIds.add(new HealthcareWorkerIdentifierMapping(id, personSlotValList));
                }

                if (localLocationID!=null) {
                    Identifier id = new Identifier(localLocationID, new AssigningAuthority("", localLocationIDAssigningAuthority));
                    enterpriseFacilityIds.add(new FacilityIdentifierMapping(id, localLocationName, institutionSlotValList));
                }
            }
        }
    }



    /**
     * @param classificationScheme - The classification scheme to look for
     * @param eo - The extrinsic object to process
     * @return A list of maps, each item in the list represents a classification definition for
     * this scheme. There may be multiple of these. Each list item contains a map of SlotType1
     * objects keyed by their slot name.
     * @throws JAXBException
     */
    protected List<Map<String, SlotType1>> getClassificationSlotsFromExtrinsicObject(String classificationScheme, ExtrinsicObjectType eo) throws JAXBException {
        List<ClassificationType> classifications = eo.getClassification();

        List<Map<String, SlotType1>> classificationMaps = new ArrayList<Map<String, SlotType1>>();
        for (ClassificationType c : classifications) {
            if (c.getClassificationScheme().equals(classificationScheme)) {
                Map<String, SlotType1> slotsFromRegistryObject = InfosetUtil.getSlotsFromRegistryObject(c);
                classificationMaps.add(slotsFromRegistryObject);
            }
        }
        return classificationMaps;
    }



    private void resolveEnterpriseIdentifiers() {
        log.info("Resolving identifiers");
        resolvePatientIdentifiers();
        resolveHealthcareWorkerIdentifiers();
        resolveFacilityIdentifiers();
    }

    private void resolvePatientIdentifiers() {
        AssigningAuthority targetPatientIdAuthority = new AssigningAuthority();
        targetPatientIdAuthority.setAssigningAuthority(config.getProperties().getProperty("pix.requestedAssigningAuthority"));
        targetPatientIdAuthority.setAssigningAuthorityId(config.getProperties().getProperty("pix.requestedAssigningAuthorityId"));

        for (IdentifierMapping mapping : enterprisePatientIds) {
            ResolvePatientIdentifier msg = new ResolvePatientIdentifier(
                    originalRequest.getRequestHandler(), getSelf(), mapping.fromId.toString(), mapping.fromId, targetPatientIdAuthority
            );
            resolvePatientIdHandler.tell(msg, getSelf());
        }
    }

    private void resolveHealthcareWorkerIdentifiers() {
        AssigningAuthority targetHealthcareWorkerIdAuthority = new AssigningAuthority();
        //TODO conf
        targetHealthcareWorkerIdAuthority.setAssigningAuthority("EPID");
        targetHealthcareWorkerIdAuthority.setAssigningAuthorityId("EPID");

        for (IdentifierMapping mapping : enterpriseHealthcareWorkerIds) {
            ResolveHealthcareWorkerIdentifier msg = new ResolveHealthcareWorkerIdentifier(
                    originalRequest.getRequestHandler(), getSelf(), mapping.fromId.toString(), mapping.fromId, targetHealthcareWorkerIdAuthority
            );
            resolveHealthcareWorkerIdHandler.tell(msg, getSelf());
        }
    }

    private void resolveFacilityIdentifiers() {
        AssigningAuthority targetFacilityIdAuthority = new AssigningAuthority();
        //TODO conf
        targetFacilityIdAuthority.setAssigningAuthority("ELID");
        targetFacilityIdAuthority.setAssigningAuthorityId("ELID");

        for (IdentifierMapping mapping : enterpriseFacilityIds) {
            ResolveFacilityIdentifier msg = new ResolveFacilityIdentifier(
                    originalRequest.getRequestHandler(), getSelf(), mapping.fromId.toString(), mapping.fromId, targetFacilityIdAuthority
            );
            resolveFacilityIdHandler.tell(msg, getSelf());
        }
    }

    private void processResolvedPatientId(ResolvePatientIdentifierResponse response) {
        enrichResolvedId(response, enterprisePatientIds);
    }

    private void processResolvedHealthcareWorkerId(ResolveHealthcareWorkerIdentifierResponse response) {
        enrichResolvedId(response, enterpriseHealthcareWorkerIds);
    }

    private void processResolvedFacilityId(ResolveFacilityIdentifierResponse response) {
        enrichResolvedId(response, enterpriseFacilityIds);
    }

    private void enrichResolvedId(BaseResolveIdentifierResponse response, List<IdentifierMapping> lst) {
        for (IdentifierMapping mapping : lst) {
            if (mapping.fromId.toString().equals(response.getOriginalRequest().getCorrelationId())) {
                mapping.resolve(response.getIdentifier());
            }
        }
    }

    private boolean checkAndRespondIfAllResolved() {
        if (areAllIdentifiersResolved()) {
            log.info("All identifiers resolved. Responding with enriched document.");
            try {
                String xml = Util.marshallJAXBObject("ihe.iti.xds_b._2007", new ObjectFactory().createProvideAndRegisterDocumentSetRequest(parsedRequest), false);
                OrchestrateProvideAndRegisterRequestResponse response = new OrchestrateProvideAndRegisterRequestResponse(originalRequest, xml);
                originalRequest.getRespondTo().tell(response, getSelf());
            } catch (JAXBException ex) {
                originalRequest.getRequestHandler().tell(new ExceptError(ex), getSelf());
            }
            return true;
        }
        return false;
    }

    private boolean areAllIdentifiersResolved() {
        return areAllIdentifiersResolvedForList(enterprisePatientIds) &&
                areAllIdentifiersResolvedForList(enterpriseHealthcareWorkerIds) &&
                areAllIdentifiersResolvedForList(enterpriseFacilityIds);
    }

    private boolean areAllIdentifiersResolvedForList(List<IdentifierMapping> lst) {
        for (IdentifierMapping mapping : lst) {
            if (!mapping.resolved) {
                return false;
            }
        }
        return true;
    }


    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof OrchestrateProvideAndRegisterRequest) {
            log.info("Orchestrating XDS.b Provide and Register request");
            originalRequest = (OrchestrateProvideAndRegisterRequest) msg;
            parseRequest((OrchestrateProvideAndRegisterRequest) msg);
        } else if (SimpleMediatorResponse.isInstanceOf(ProvideAndRegisterDocumentSetRequestType.class, msg)) {
            processParsedRequest(((SimpleMediatorResponse<ProvideAndRegisterDocumentSetRequestType>) msg).getResponseObject());
        } else if (msg instanceof ResolvePatientIdentifierResponse) {
            processResolvedPatientId((ResolvePatientIdentifierResponse) msg);
            checkAndRespondIfAllResolved();
        } else if (msg instanceof ResolveHealthcareWorkerIdentifierResponse) {
            processResolvedHealthcareWorkerId((ResolveHealthcareWorkerIdentifierResponse) msg);
            checkAndRespondIfAllResolved();
        } else if (msg instanceof ResolveFacilityIdentifierResponse) {
            processResolvedFacilityId((ResolveFacilityIdentifierResponse) msg);
            checkAndRespondIfAllResolved();
        } else {
            unhandled(msg);
        }
    }


}