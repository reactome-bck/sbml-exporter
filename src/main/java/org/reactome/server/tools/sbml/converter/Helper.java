package org.reactome.server.tools.sbml.converter;

import org.reactome.server.graph.domain.model.*;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.tools.sbml.data.model.ParticipantDetails;
import org.reactome.server.tools.sbml.data.model.ReactionBase;
import org.sbml.jsbml.*;
import org.sbml.jsbml.Reaction;
import org.sbml.jsbml.Species;
import org.sbml.jsbml.xml.XMLNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.sbml.jsbml.JSBML.getJSBMLDottedVersion;

/**
 * Contains methods that are commonly used in the {@link SbmlConverter}
 *
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 * @author Kostas Sidiropoulos (ksidiro@ebi.ac.uk)
 * @author Sarah Keating (skeating@ebi.ac.uk)
 */
class Helper {

    private static Logger logger = LoggerFactory.getLogger("sbml-exporter");

    private static final String REACTOME_URI = "https://reactome.org/content/detail/";

    static void addAnnotations(Species s, ParticipantDetails participant) {
        PhysicalEntity pe = participant.getPhysicalEntity();

        Helper.addNotes(s, participant.getExplanation());

        List<String> summations = new ArrayList<>();
        for (Summation summation : pe.getSummation()) {
            String text = summation.getText();
            if (text != null) {
                summations.add(text);
            }
        }
        Helper.addNotes(s, summations);

        List<String> litrefs = new ArrayList<>();
        for (Publication l : participant.getPhysicalEntity().getLiteratureReference()) {
            if (l instanceof LiteratureReference) {
                String url = ((LiteratureReference) l).getUrl();
                litrefs.add(url);
            }
        }
        Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS_DESCRIBED_BY, litrefs);

        if (pe instanceof Complex || pe instanceof EntitySet || pe instanceof Polymer) {
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS, REACTOME_URI + pe.getStId());
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_HAS_PART, participant.getUrls());
        } else {
            participant.addUrl(REACTOME_URI + pe.getStId());
            Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS, participant.getUrls());

            if (pe instanceof EntityWithAccessionedSequence) {
                EntityWithAccessionedSequence ewas = (EntityWithAccessionedSequence) pe;

                List<String> psis = new ArrayList<>();
                for (AbstractModifiedResidue r : ewas.getHasModifiedResidue()) {
                    if (r instanceof TranslationalModification && ((TranslationalModification) r).getPsiMod() != null) {
                        String url = ((TranslationalModification) r).getPsiMod().getUrl();
                        psis.add(url);
                    }
                }
                Helper.addCVTerm(s, CVTerm.Qualifier.BQB_HAS_VERSION, psis);
            }
        }

        List<String> infTos = new ArrayList<>();
        for (PhysicalEntity p : pe.getInferredTo()) {
            String s1 = REACTOME_URI + p.getStId();
            infTos.add(s1);
        }
        Helper.addCVTerm(s, CVTerm.Qualifier.BQB_IS_HOMOLOG_TO, infTos);

        List<String> infFroms = new ArrayList<>();
        for (PhysicalEntity p : pe.getInferredFrom()) {
            String s1 = REACTOME_URI + p.getStId();
            infFroms.add(s1);
        }
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
            for (Person c : created.getAuthor()) Helper.addCreator(history, c);
            history.setCreatedDate(Helper.formatDate(created.getDateTime()));
        }

        InstanceEdit modified = event.getModified();
        if (modified != null) {
            for (Person m : modified.getAuthor()) Helper.addCreator(history, m);
            history.addModifiedDate(Helper.formatDate(modified.getDateTime()));
        }

        for (InstanceEdit authored : event.getAuthored()) {
            for (Person a : authored.getAuthor()) Helper.addCreator(history, a);
            history.addModifiedDate(Helper.formatDate(authored.getDateTime()));
        }

        for (InstanceEdit revised : event.getRevised()) {
            for (Person r : revised.getAuthor()) Helper.addCreator(history, r);
            history.addModifiedDate(Helper.formatDate(revised.getDateTime()));
        }

        Annotation annotation = new Annotation();
        annotation.setHistory(history);

        List<String> summations = new ArrayList<>();
        for (Summation summation : event.getSummation()) {
            String text = summation.getText();
            if (text != null) {
                summations.add(text);
            }
        }
        Helper.addNotes(sBase, summations);

        List<String> uris = new ArrayList<>();
        uris.add(REACTOME_URI + event.getStId());
        GO_BiologicalProcess go = event.getGoBiologicalProcess();
        if (go != null) uris.add(go.getUrl());
        Helper.addCVTerm(annotation, CVTerm.Qualifier.BQB_IS, uris);

        List<String> litRefs = new ArrayList<>();
        for (Publication p : event.getLiteratureReference()) {
            if (p instanceof LiteratureReference) {
                String url = ((LiteratureReference) p).getUrl();
                if (url != null) {
                    litRefs.add(url);
                }
            }
        }
        Helper.addCVTerm(annotation, CVTerm.Qualifier.BQB_IS_DESCRIBED_BY, litRefs);

        sBase.setAnnotation(annotation);
    }

    private static void addCVTerm(SBase sBase, CVTerm.Qualifier qualifier, List<String> uris) {
        addCVTerm(sBase, qualifier, uris.toArray(new String[0]));
    }

    static void addCVTerm(SBase sBase, CVTerm.Qualifier qualifier, String... uris) {
        if (uris.length > 0) {
            CVTerm term = new CVTerm(qualifier);
            for (String s : uris) term.addResourceURI(s);
            sBase.addCVTerm(term);
        }
    }

    private static void addCVTerm(Annotation annotation, CVTerm.Qualifier qualifier, List<String> uris) {
        if (!uris.isEmpty()) {
            CVTerm term = new CVTerm(qualifier);
            for (String s : uris) term.addResourceURI(s);
            annotation.addCVTerm(term);
        }
    }

    private static void addNotes(SBase sBase, List<String> content) {
        if (content != null) addNotes(sBase, content.toArray(new String[0]));
    }

    static synchronized void addNotes(SBase sBase, String... content) {
        if (content != null && content.length > 0) {
            StringJoiner joiner = new StringJoiner(System.lineSeparator(), "<notes><p xmlns=\"http://www.w3.org/1999/xhtml\">", "</p></notes>");
            for (String s : content) {
                if (s != null) {
                    String removeTags = removeTags(s);
                    joiner.add(removeTags);
                }
            }
            String notes = joiner.toString();

            try {
                XMLNode node = XMLNode.convertStringToXMLNode(notes);
                sBase.appendNotes(node);
            } catch (XMLStreamException e) {
                logger.error(String.format("An error occurred while generating notes for '%s'", sBase.getId()), e);
            }
        }
    }

    /**
     * Adds information about the reactomeDB version and jsbml version
     */
    static void addProvenanceAnnotation(SBase sBase, Integer version) {
        Date date = new Date();
        DateFormat dateFormat = new SimpleDateFormat();
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
            logger.error(String.format("An error occurred while generating the provenance annotation for '%s'", sBase.getId()), e);
        }
    }

    static void addSBOTerm(SBase sBase, Integer term) {
        if (term >= 0 && term <= 9999999) {
            sBase.setSBOTerm(term);
        }
    }

    private static void addCreator(History history, Person person) {
        Creator creator = new Creator();
        creator.setFamilyName(person.getSurname() == null ? "" : person.getSurname());
        creator.setGivenName(person.getFirstname() == null ? "" : person.getFirstname());
        for (Affiliation a : person.getAffiliation()) creator.setOrganisation(a.getName().get(a.getName().size() - 1));
        history.addCreator(creator);
    }

    /**
     * Remove any html tags from the text.
     *
     * @param notes String to be adjusted.
     * @return String with any <></> removed.
     */
    private static String removeTags(String notes) {
        // if we have an xhtml tags in the text it messes up parsing copied from old reactome code with some additions
        return notes.replaceAll("<->", " to ")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("&+", " and ")
                .replaceAll("<>", " interconverts to ")
                .replaceAll("\n+", "  ")
                //.replaceAll("</*[a-zA-Z][^>]*>", " ")
                .replaceAll("<.*?>", "")
                .replaceAll("<", " ");
    }

    /**
     * Creates a Date object from the string stored in ReactomeDB.
     *
     * @param datetime String the date times as stored in ReactomeDB
     * @return Date object created from the String or null if this
     * cannot be parsed.
     */
    private static Date formatDate(String datetime) {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.ENGLISH);
        try {
            return format.parse(datetime);
        } catch (ParseException e) {
            return null;
        }
    }
}
