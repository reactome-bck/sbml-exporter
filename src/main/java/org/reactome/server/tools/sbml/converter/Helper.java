package org.reactome.server.tools.sbml.converter;

import org.reactome.server.graph.domain.model.*;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.service.GeneralService;
import org.reactome.server.graph.utils.ReactomeGraphCore;
import org.reactome.server.tools.sbml.data.model.ParticipantDetails;
import org.reactome.server.tools.sbml.data.model.ReactionBase;
import org.sbml.jsbml.*;
import org.sbml.jsbml.Reaction;
import org.sbml.jsbml.Species;
import org.sbml.jsbml.xml.XMLNode;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.sbml.jsbml.JSBML.getJSBMLDottedVersion;

/**
 * Contains methods that are commonly used in the {@link SbmlConverter}
 *
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 * @author Kostas Sidiropoulos (ksidiro@ebi.ac.uk)
 * @author Sarah Keating (skeating@ebi.ac.uk)
 */
class Helper {

    private static final String REACTOME_URI = "https://reactome.org/content/detail/";

    /**
     * Open and closing tags for notes elements
     */
    private static String OPEN_NOTES = "<notes><p xmlns=\"http://www.w3.org/1999/xhtml\">";
    private static String CLOSE_NOTES = "</p></notes>";

    static void addAnnotations(Species s, ParticipantDetails participant) {
        PhysicalEntity pe = participant.getPhysicalEntity();

        Helper.addNotes(s, participant.getExplanation());

        List<String> summations = pe.getSummation()
                .stream()
                .filter(su -> su.getText() != null)
                .map(Summation::getText)
                .collect(Collectors.toList());
        Helper.addNotes(s, summations);

        List<String> litrefs = participant.getPhysicalEntity().getLiteratureReference()
                .stream()
                .filter(l -> l instanceof LiteratureReference)
                .map(l -> ((LiteratureReference) l).getUrl())
                .collect(Collectors.toList());
        Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS_DESCRIBED_BY, litrefs);

        if (pe instanceof Complex || pe instanceof EntitySet || pe instanceof Polymer) {
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS, REACTOME_URI + pe.getStId());
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_HAS_PART, participant.getUrls());
        } else {
            participant.addUrl(REACTOME_URI + pe.getStId());
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS, participant.getUrls());

            if (pe instanceof EntityWithAccessionedSequence) {
                EntityWithAccessionedSequence ewas = (EntityWithAccessionedSequence) pe;

                List<String> psis = ewas.getHasModifiedResidue()
                        .stream()
                        .filter(r -> r instanceof TranslationalModification && ((TranslationalModification) r).getPsiMod() != null)
                        .map(r -> ((TranslationalModification) r).getPsiMod().getUrl())
                        .collect(Collectors.toList());
                Helper.addCVTerm(s, CVTerm.Qualifier.BQB_HAS_VERSION, psis);
            }
        }

        List<String> infTos = pe.getInferredTo()
                .stream()
                .map(p -> REACTOME_URI + p.getStId())
                .collect(Collectors.toList());
        Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS_HOMOLOG_TO, infTos);

        List<String> infFroms = pe.getInferredFrom()
                .stream()
                .map(p -> REACTOME_URI + p.getStId())
                .collect(Collectors.toList());
        Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS_HOMOLOG_TO, infFroms);
    }

    static void addCVTerms(Reaction reaction, ReactionBase rxn) {
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQB_IS, REACTOME_URI + rxn.getStId());
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQB_IS, rxn.getGoTerms());
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQB_IS, rxn.getEcNumbers());
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQB_IS_DESCRIBED_BY, rxn.getLiteratureRefs());
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQB_OCCURS_IN, rxn.getDiseases());
        Helper.addCVTerm(reaction, CVTerm.Qualifier.BQM_HAS_INSTANCE, rxn.getCrossReferences());
    }

    static void addAnnotations(SBase sBase, Event event) {
        //When converting an orphan reaction, this event is null and no annotations have to be added
        if(event == null) return;

        History history = new History();
        InstanceEdit created = event.getCreated();
        if (created != null) {
            created.getAuthor().forEach(c -> Helper.addCreator(history, c));
            history.setCreatedDate(Helper.formatDate(created.getDateTime()));
        }

        InstanceEdit modified = event.getModified();
        if (modified != null) {
            modified.getAuthor().forEach(m -> Helper.addCreator(history, m));
            history.addModifiedDate(Helper.formatDate(modified.getDateTime()));
        }

        for (InstanceEdit authored : event.getAuthored()) {
            authored.getAuthor().forEach(a -> Helper.addCreator(history, a));
            history.addModifiedDate(Helper.formatDate(authored.getDateTime()));
        }

        for (InstanceEdit revised : event.getRevised()) {
            revised.getAuthor().forEach(r -> Helper.addCreator(history, r));
            history.addModifiedDate(Helper.formatDate(revised.getDateTime()));
        }

        Annotation annotation = new Annotation();
        annotation.setHistory(history);

        List<String> summations = event.getSummation()
                .stream()
                .filter(s -> s.getText() != null)
                .map(Summation::getText)
                .collect(Collectors.toList());
        Helper.addNotes(sBase, summations);

        List<String> uris = new ArrayList<>();
        uris.add(REACTOME_URI + event.getStId());
        GO_BiologicalProcess go = event.getGoBiologicalProcess();
        if (go != null) uris.add(go.getUrl());
        Helper.addCVTerm(annotation, CVTerm.Qualifier.BQB_IS, uris);

        List<String> litRefs = event.getLiteratureReference()
                .stream()
                .filter(s -> s instanceof LiteratureReference)
                .map(p -> ((LiteratureReference) p).getUrl())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Helper.addCVTerm(annotation, CVTerm.Qualifier.BQB_IS_DESCRIBED_BY, litRefs);

        sBase.setAnnotation(annotation);
    }

    static void addCVTerm(SBase sBase, CVTerm.Qualifier qualifier, List<String> uris) {
        addCVTerm(sBase, qualifier, uris.toArray(new String[uris.size()]));
    }

    static void addCVTerm(SBase sBase, CVTerm.Qualifier qualifier, String... uris) {
        if (uris.length > 0) {
            CVTerm term = new CVTerm(qualifier);
            Arrays.stream(uris).forEach(term::addResourceURI);
            sBase.addCVTerm(term);
        }
    }

    static void addCVTerm(Annotation annotation, CVTerm.Qualifier qualifier, List<String> uris) {
        if (!uris.isEmpty()) {
            CVTerm term = new CVTerm(qualifier);
            uris.forEach(term::addResourceURI);
            annotation.addCVTerm(term);
        }
    }

    static void addNotes(SBase sBase, List<String> content) {
        if (content != null) addNotes(sBase, content.toArray(new String[0]));
    }

    static void addNotes(SBase sBase, String... content) {
        if (content != null && content.length > 0) {
            String notes = Arrays.stream(content)
                    .filter(Objects::nonNull)
                    .map(Helper::removeTags)
                    .collect(Collectors.joining(System.lineSeparator(), OPEN_NOTES, CLOSE_NOTES));
            try {
                XMLNode node = XMLNode.convertStringToXMLNode(notes);
                sBase.appendNotes(node);
            } catch (Exception e) {
                System.out.println(notes);
                e.printStackTrace(); //TODO: log this situation
            }
        }
    }

    /**
     * Adds information about the reactomeDB version and jsbml version
     */
    static void addProvenanceAnnotation(SBase sBase) {
        Date date = new Date();
        DateFormat dateFormat = new SimpleDateFormat();
        Integer version = ReactomeGraphCore.getService(GeneralService.class).getDBInfo().getVersion();
        String jsbml = String.format("" +
                        "<annotation>" +
                        "<p xmlns=\"http://www.w3.org/1999/xhtml\">" +
                        "SBML generated from Reactome version %d on %s using JSBML version %s." +
                        "</p>" +
                        "</annotation>",
                version, dateFormat.format(date), getJSBMLDottedVersion());

        try {
            XMLNode node = XMLNode.convertStringToXMLNode(jsbml);
            sBase.appendNotes(node);
        } catch (Exception e) {
            e.printStackTrace(); //TODO: log this situation
        }
    }

    static void addSBOTerm(SBase sBase, Integer term) {
        if (term >= 0 && term <= 9999999) {
            sBase.setSBOTerm(term);
        }
    }

    /**
     * Creates an SBML Creator object from the information from ReactomeDB.
     *
     * @param person Person from ReactomeDB
     * @return Creator object for JSBML
     */
    static void addCreator(History history, Person person) {
        Creator creator = new Creator();
        creator.setFamilyName(person.getSurname() == null ? "" : person.getSurname());
        creator.setGivenName(person.getFirstname() == null ? "" : person.getFirstname());
        person.getAffiliation().forEach(a -> creator.setOrganisation(a.getName().get(a.getName().size() - 1)));
        history.addCreator(creator);
    }


    /**
     * Remove any html tags from the text.
     *
     * @param notes String to be adjusted.
     * @return String with any <></> removed.
     */
    static String removeTags(String notes) {
        // if we have an xhtml tags in the text it messes up parsing copied from old reactome code with some additions
        return notes.replaceAll("<->", " to ")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("&+", " and ")
                .replaceAll("<>", " interconverts to ")
                .replaceAll("\n+", "  ")
                //.replaceAll("</*[a-zA-Z][^>]*>", " ")
                .replaceAll("\\<.*?\\>", "")
                .replaceAll("<", " ");
    }

    /**
     * Creates a Date object from the string stored in ReactomeDB.
     *
     * @param datetime String the date times as stored in ReactomeDB
     * @return Date object created from the String or null if this
     * cannot be parsed.
     */
    static Date formatDate(String datetime) {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.ENGLISH);
        try {
            return format.parse(datetime);
        } catch (ParseException e) {
            return null;
        }
    }
}