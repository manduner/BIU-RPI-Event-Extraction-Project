package edu.cuny.qc.perceptron.types;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.jcas.JCas;

import com.google.common.collect.Lists;

import ac.biu.nlp.nlp.ie.onthefly.input.SpecAnnotator;
import ac.biu.nlp.nlp.ie.onthefly.input.TypesContainer;
import ac.biu.nlp.nlp.ie.onthefly.input.uima.Argument;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import edu.cuny.qc.ace.acetypes.AceDocument;
import edu.cuny.qc.ace.acetypes.AceEntityMention;
import edu.cuny.qc.ace.acetypes.AceEvent;
import edu.cuny.qc.ace.acetypes.AceEventMention;
import edu.cuny.qc.ace.acetypes.AceEventMentionArgument;
import edu.cuny.qc.ace.acetypes.AceMention;
import edu.cuny.qc.perceptron.core.Controller;
import edu.cuny.qc.perceptron.core.Perceptron;
import edu.cuny.qc.perceptron.core.Pipeline;
import edu.cuny.qc.perceptron.graph.DependencyGraph;
import edu.cuny.qc.perceptron.types.Sentence.Sent_Attribute;
import edu.cuny.qc.scorer.ScorerData;
import edu.cuny.qc.scorer.SignalMechanism;
import edu.cuny.qc.scorer.SignalMechanismException;
import edu.cuny.qc.util.FinalKeysMap;
import edu.cuny.qc.util.Span;

/**
 * This is a basic object of the learning algrithm
 * it represents a sentence, including text features target assignments, beam in searching etc.
 * 
 * For the text features, it should contain feature vectors for each token in the sentence
 * and the original rich representation of the sentence, e.g. dependency parse tree etc.
 * 
 * For the (target) assignment, it should encode two types of assignment:
 * (1) label assignment for each token: refers to the event trigger classification 
 * (2) assignment for any sub-structure of the sentence, e.g. one assignment indicats that 
 * the second token is argument of the first trigger
 * 
 * Given the first type of assigment, it should be able to get features for the learning algorithm, e.g. token feature vector X assignment
 * similarly, given the second type of assignment, it should be able to get features like: text features assoicated with tokens X assignment
 * Finally, on top of the assignment, it should be able to get arbitrary features, e.g. count how many "triggers" accur in this sentence
 * @author che
 *
 */
public class SentenceInstance
{
	static {
		System.err.println("SentenceInstance: yes, even here I removed the args :) Epecially at the signal calcing thing (but not only)");
	}
	
	public boolean learnable = false;
	
	public transient TypesContainer types;
	
	// the alphabet of the label for each node (token of trigger)
	public Alphabet nodeTargetAlphabet;
	
	// the alphabet of the label for each edge (trigger-->argument link)
	public Alphabet edgeTargetAlphabet;
	
	// the alphabet of features, shared by the whole application
	public Alphabet featureAlphabet;
	
	// the settings of the whole perceptron
	public Controller controller;
	
	// the ground-truth assignment for the sentence
	public SentenceAssignment target; 
	
	// the text of the original doc
	public String allText;
	
	public String docID;
	
	public Integer sentID;
	public String sentInstID;

	
	/**
	 * Ofer: Add this for debugging purposes
	 */
	public String textStart;
	
	private List<Token> tokenAnnos = null;
	private de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence sentenceAnno = null;
	private Document doc;
	public JCas associatedSpec = null;
	private Boolean filtered = false;

	/**
	 * the list of argument candidates (values/entities/timex)
	 */
	public List<AceMention> eventArgCandidates = new ArrayList<AceMention>(); 
	
	/**
	 * the list of event mentions 
	 */
	public List<AceEventMention> eventMentions;
	
	/**
	 * a sequence of token, each token is a vector of features
	 * this is useful for the beam search 
	 */
	Map<InstanceAnnotations, Object> textFeaturesMap = new HashMap<InstanceAnnotations, Object>(); 
	
	static public enum InstanceAnnotations
	{
		Token_FEATURE_MAPs,			// list->map<key,value> token feature maps, each map contains basic text features for a token
		DepGraph,	  				// dependency: Collection<TypedDependency> or other kind of data structure
		TOKEN_SPANS,				// List<Span>: the spans of each token in this sent
		POSTAGS,					// POS tags
		NodeTextSignalsBySpec,		// node feature Vectors
		EdgeTextSignals,		// node feature Vectors
		ParseTree,					// parse tree
		SentenceAnnotation,         // UIMA Sentence Annotation
		TokenAnnotations,           // UIMA Token Annotations
	}
	
	@Override
	public String toString() {
		try {
			final int TEXT_DISPLAY_MAX = 10;
			String label = (associatedSpec==null) ? "*" : SpecAnnotator.getSpecLabel(associatedSpec);
			return String.format("%s(%s, %d events, %d argcands: %s...)", sentInstID, label, eventMentions.size(), eventArgCandidates.size(), StringUtils.substring(textStart, 0, TEXT_DISPLAY_MAX));
		} catch (CASException e) {
			throw new RuntimeException(e);
		}
	}
	
	public Object get(InstanceAnnotations key)
	{
		return textFeaturesMap.get(key);
	}
	
	public Span[] getTokenSpans()
	{
		return (Span[]) textFeaturesMap.get(InstanceAnnotations.TOKEN_SPANS);
	}
	
	public String[] getPosTags()
	{
		return (String[]) textFeaturesMap.get(InstanceAnnotations.POSTAGS);
	}
	
	public List<Map<Class<?>, Object>> getTokenFeatureMaps()
	{
		return (List<Map<Class<?>, Object>>) textFeaturesMap.get(InstanceAnnotations.Token_FEATURE_MAPs);
	}
	
	public SentenceInstance(TypesContainer types, Alphabet featureAlphabet, 
			Controller controller, boolean learnable, boolean debug)
	{
		this.types = types;
		this.nodeTargetAlphabet = types.nodeTargetAlphabet;
		this.edgeTargetAlphabet = types.edgeTargetAlphabet;
		this.featureAlphabet = featureAlphabet;
		this.controller = controller;
		this.learnable = learnable;
	}
	
	/**
	 * use sentence instance to initialize the training instance
	 * the SentenceInstance object can also be initialized by a file
	 * @param sent
	 */
	public SentenceInstance(Perceptron perceptron, Sentence sent, TypesContainer types, Alphabet featureAlphabet, 
			boolean learnable, JCas associatedSpec, Integer specNum, boolean debug)
	{
		this(types, featureAlphabet, perceptron.controller, learnable, debug);
		
		//System.out.printf("%s Starting c-tor SentenceInstance %s...\n", Pipeline.detailedLog(), this.sentInstID);

		// set the text of the doc
		this.allText = sent.doc.allText;
		this.docID = sent.doc.docID;
		this.textStart = sent.text;
		this.doc = sent.doc;
		this.sentID = sent.sentID;
		if (specNum != null) {
			this.sentInstID =  calcSentInstID(sent.sentID, specNum);
		}
		else {
			this.sentInstID = Integer.toString(sent.sentID);
		}
		//this.sentID = sent.sentID;
		this.associatedSpec = associatedSpec;

		// fill in entity information
		this.eventArgCandidates.addAll(sent.entityMentions);
		this.eventArgCandidates.addAll(sent.valueMentions);
		this.eventArgCandidates.addAll(sent.timexMentions);
		
		/// DEBUG
//		if (docID.equals("src\\main\\resources\\corpus\\qi\\bc/timex2norm/CNN_CF_20030304.1900.04") &&
//				sentID == 65) {
//			System.out.printf("");
//		}

		//////
		
		// sort event Arg candidates by order of offsets
		Collections.sort(this.eventArgCandidates, new Comparator<AceMention>()
				{
					@Override
					public int compare(AceMention arg0, AceMention arg1)
					{
						int begin0 = arg0.extent.start();
						int begin1 = arg1.extent.start();
						if(arg0 instanceof AceEntityMention)
						{
							begin0 = ((AceEntityMention) arg0).head.start();
						}
						if(arg1 instanceof AceEntityMention)
						{
							begin1 = ((AceEntityMention) arg1).head.start();
						}
						if(begin0 > begin1)
						{
							return 1;
						}
						else if(begin0 == begin1)
						{
							return 0;
						}
						else
						{
							return -1;
						}
					}
				}
			);
		
		// fill in token text feature maps
		this.textFeaturesMap.put(InstanceAnnotations.Token_FEATURE_MAPs, sent.get(Sent_Attribute.Token_FEATURE_MAPs));
		
		// fill in Annotations map with dependency paths, later we can even fill in parse tree etc.
		DependencyGraph graph = (DependencyGraph) sent.get(Sent_Attribute.DepGraph);
		this.textFeaturesMap.put(InstanceAnnotations.DepGraph, graph);
		
		// fill in parse tree
		this.textFeaturesMap.put(InstanceAnnotations.ParseTree, sent.get(Sent_Attribute.ParseTree));
		
		// fill in tokens and pos tags
		this.textFeaturesMap.put(InstanceAnnotations.TOKEN_SPANS, sent.get(Sent_Attribute.TOKEN_SPANS));
		this.textFeaturesMap.put(InstanceAnnotations.POSTAGS, sent.get(Sent_Attribute.POSTAGS));
		
		this.textFeaturesMap.put(InstanceAnnotations.SentenceAnnotation, sent.get(Sent_Attribute.SentenceAnnotation));
		this.textFeaturesMap.put(InstanceAnnotations.TokenAnnotations, sent.get(Sent_Attribute.TokenAnnotations));
		tokenAnnos = new ArrayList<Token>(Collections.nCopies(size(), (Token) null));

		// get node text feature vectors
//		List<Map<String, Map<String, SignalInstance>>> tokenSignalBySpec = NodeSignalGenerator.get_node_text_signals(this, perceptron);
//		this.textFeaturesMap.put(InstanceAnnotations.NodeTextSignalsBySpec, tokenSignalBySpec);
//		
//		// get edge text feature vectors, this vectors is built up in the lasy fashion, when it's needed, it's filled
//		List<List<List<String>>> edgeSignals = new ArrayList<List<List<String>>>();
//		for(int i=0; i<size(); i++)
//		{
//			List<List<String>> signalsForNode = new ArrayList<List<String>>();
//			edgeSignals.add(signalsForNode);
//			for(int j=0; j<eventArgCandidates.size(); j++)
//			{
//				signalsForNode.add(null);
//			}
//		}
//		this.textFeaturesMap.put(InstanceAnnotations.EdgeTextSignals, edgeSignals);
		
		// add event ground-truth
		eventMentions = new ArrayList<AceEventMention>();
		eventMentions.addAll(sent.eventMentions);
		
		if (associatedSpec != null) {
			AceDocument.filterBySpecs(types, filtered, eventMentions, null, null, null, null, null, null, null, null);
		}
		
		getPersistentSignals(perceptron, debug);
		
		//System.out.printf("%s Starting target of SentenceInstance %s...\n", Pipeline.detailedLog(), this.sentInstID);

		// add target as gold-standard assignment
		this.target = new SentenceAssignment(this, perceptron);
		
		//System.out.printf("%s Finishing c-tor SentenceInstance %s...\n", Pipeline.detailedLog(), this.sentInstID);

	}

	/***
	 * Works only when specNum is in 0..51!
	 * <br>specNum conversion:
	 * <br>0 --> a
	 * <br>1 --> b
	 * <br>25 --> z
	 * <br>26 --> A
	 * <br>51 --> Z
	 * <br>52 --> IllegalArgumentException
	 * <br>53 --> IllegalArgumentException
	 * <br>-1 --> IllegalArgumentException
	 */
	public static String calcSentInstID(int id, int specNum) {
		char mark;
		if (specNum >= 0 && specNum <= 'z' - 'a') {
			mark = (char) ('a' + specNum);
		}
		else {
			int s = specNum - ('z' - 'a' + 1);
			if (specNum >= 0 && s <= 'Z' - 'A') {
				mark = (char) ('A' + s);
			}
			else {
				throw new IllegalArgumentException(String.format("Given specNum out of range, got %d, can handle only 0..%d", specNum, 'z'-'a' + 'Z'-'A'+1));
			}
		}
		return String.format("%d%c", id, mark);
	}
	
	/**
	 * the size of the sentence
	 * @return
	 */
	public int size()
	{
		return this.getTokenSpans().length;
	}
	
	public Token getTokenAnnotation(int i) {
		Token token = tokenAnnos.get(i);
		if (token == null) {
			List<Integer> tokenAddrs = (List<Integer>) this.get(InstanceAnnotations.TokenAnnotations);
			Integer addr = tokenAddrs.get(i);
			FeatureStructure fs  = doc.jcas.getLowLevelCas().ll_getFSForRef(addr);
			token = (Token) fs;
			tokenAnnos.set(i, token);
		}
		return token;
	}
	
	public de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence getSentenceAnnotation() {
		if (sentenceAnno == null) {
			Integer addr = (Integer) this.get(InstanceAnnotations.SentenceAnnotation);
			sentenceAnno = (de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence) doc.jcas.getLowLevelCas().ll_getFSForRef(addr);
		}
		return sentenceAnno;
	}
	
	/**
	 * given a SentenceAssignment, convert the results as List of AceEventMentions
	 * @param assn
	 * @return
	 */
	public List<AceEvent> getEvents(SentenceAssignment assn, String id, String fileText)
	{
		List<AceEvent> ret = new ArrayList<AceEvent>();		
		for(int i=0; i<assn.nodeAssignment.size(); i++)
		{
			Integer trigger_label = assn.nodeAssignment.get(i);
			String label = (String) this.nodeTargetAlphabet.lookupObject(trigger_label);
			if(label != null && !label.equals(SentenceAssignment.Default_Trigger_Label))
			{
				// only put event subtype and id
				// 20.3.2014 Ofer's note:
				// The usage of TypeConstraints.eventTypeMap here is legitimate, as it's used in order to get
				// the "top type" (like "Life") from subtype (like "Be-Born"), because it's required for the
				// AceEvent object. We gotta have a static list of this, as this info doesn't appear in the specs.
				//AceEvent event = new AceEvent(id, TypeConstraints.eventTypeMap.get(label), label); 
				AceEvent event = new AceEvent(id, "", label); 
				
				// not NON
				Span trigger_span = this.getTokenSpans()[i];
				String mention_id = id + "-1";
				AceEventMention mention = new AceEventMention(mention_id, trigger_span, fileText, null);
				
				// set extent of the event mention
				Span[] tokenSpans = (Span[]) this.get(InstanceAnnotations.TOKEN_SPANS);
				int extent_start = tokenSpans[0].start();
				int extent_end = tokenSpans[tokenSpans.length - 1].end();
				Span extent  = new Span(extent_start, extent_end);
				mention.extent = extent;
				mention.text = extent.getCoveredText(fileText);
				
				// find all arguments
				Map<Integer, Integer> edgeMap = assn.edgeAssignment.get(i);
				if(edgeMap != null)
				{
					for(Integer arg_index : edgeMap.keySet())
					{
						Integer role_index = edgeMap.get(arg_index);
						String role = (String) this.edgeTargetAlphabet.lookupObject(role_index);
						if(role != null && !role.equals(SentenceAssignment.Default_Argument_Label))
						{
							AceEventMentionArgument argument = new AceEventMentionArgument(this.eventArgCandidates.get(arg_index), role, mention);
							mention.addArgument(argument);
						}
					}
				}
				event.addMention(mention);
				ret.add(event);
			}
		}
		
		return ret;		
	}
	
	/**
	 * check if the assignment is correct up to current assn.getState()
	 * @param assn
	 * @return
	 */
	public boolean violateGoldStandard(SentenceAssignment assn)
	{
		// if there isn't "target" in this, that means this is not for learning
		if(target == null)
		{
			return false;
		}
		return !assn.equals(this.target, this.target.state);
	}
	
	/**
	 * compare a set of assignments with gold standard 
	 * @param beam
	 * @return true if violation false if not violation 
	 */
	public boolean violateGoldStandard(List<SentenceAssignment> beam)
	{
		for(SentenceAssignment assn : beam)
		{
			if(assn.equals(this.target, assn.state))
			{
				return false;
			}
		}
		return true;
	}
	
	/**
	 * check if the assignment is correct up to current assn.getState(), 
	 * but only consider k-th argument labeling (labeling for k-th entity)
	 * @param beam
	 * @param argNum: the number of entity
	 * @return
	 */
	public boolean violateGoldStandard(List<SentenceAssignment> beam, int argNum)
	{
		for(SentenceAssignment assn : beam)
		{
			if(assn.equals(this.target, assn.state, argNum))
			{
				return false;
			}
		}
		return true;
	}
	
	public boolean violateGoldStandard(SentenceAssignment assn, int argNum)
	{
		// if there isn't "target" in this, that means this is not for learning
		if(target == null)
		{
			return false;
		}
		return !assn.equals(this.target, this.target.state, argNum);
	}
	
	
	/// Ofer's new section - calcing signals!
	private void getPersistentSignals(Perceptron perceptron, boolean debug) {
		try {
//			Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>> allTriggerSignals = null;
//			Map<Integer, List<Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>>>> allArgSignals = null;
			if (doc.signals == null) {
				doc.signals = new BundledSignals(/*types,*/ /*perceptron, allTriggerSignals, allArgSignals*/);
//				allTriggerSignals = doc.signals.triggerSignals;
//				allArgSignals = doc.signals.argSignals;
				markSignalUpdate();
			}
//			else {
//				allTriggerSignals = doc.signals.triggerSignals;
//				allArgSignals = doc.signals.argSignals;
//			}
			
			List<Map<String, Map<ScorerData, SignalInstance>>> sentenceTriggerSignals;
			List<Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>>> sentenceArgSignals;
			if (!doc.signals.triggerSignals.containsKey(sentID)) {
				sentenceTriggerSignals = Lists.newArrayListWithCapacity(size());
				sentenceArgSignals = new ArrayList<Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>>>(size());
				doc.signals.triggerSignals.put(sentID, sentenceTriggerSignals);
				doc.signals.argSignals.put(sentID, sentenceArgSignals);
			}
			else {
				sentenceTriggerSignals = doc.signals.triggerSignals.get(sentID);
				sentenceArgSignals = doc.signals.argSignals.get(sentID);
			}
			
			this.textFeaturesMap.put(InstanceAnnotations.NodeTextSignalsBySpec, sentenceTriggerSignals);
			this.textFeaturesMap.put(InstanceAnnotations.EdgeTextSignals, sentenceArgSignals);
			calculatePersistentSignals(perceptron, sentenceTriggerSignals, sentenceArgSignals, debug);
		} catch (SignalMechanismException e) {
			throw new RuntimeException(e);
		} catch (CASException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void calculatePersistentSignals(Perceptron perceptron,
			List<Map<String, Map<ScorerData, SignalInstance>>> triggerSignals,
			List<Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>>> argSignals,
			boolean debug) throws SignalMechanismException, CASException {
		//List<Map<String, Map<String, SignalInstance>>> triggerSignals = new ArrayList<Map<String, Map<String, SignalInstance>>>(size());
		//List<Map<String, List<Map<String, Map<String, SignalInstance>>>>> argSignals = new ArrayList<Map<String, List<Map<String, Map<String, SignalInstance>>>>>(size());
		//System.out.printf("%s Starting signals SentenceInstance %s...\n", Pipeline.detailedLog(), this.sentInstID);
		perceptron.logSignalMechanismsPreSentence();
		for(int i=0; i<size(); i++)
		{

			/****
			
			// Add here the check that this token can be valid as a trigger - to avoid building signals when it's not needed
			// if it's not - still add something (null) to the list as a placeholder, to keep positions in the list correct
			// 26.5.14: No, always calculate signals. That's because in the current method ("F"), the feature values for an "O"
			// label are defined as being the same as the non-O (e.g., the Attack signals)
			// By the way, this is true for arguments as well.
			Map<String, Map<String, SignalInstance>> tokenTriggerSignals = null;
			Map<String, List<Map<String, Map<String, SignalInstance>>>> tokenArgSignals = null;
//			if (types.isPossibleTriggerByPOS(this, i) && types.isPossibleTriggerByEntityType(this, i)) {

				tokenTriggerSignals = new LinkedHashMap<String, Map<String, SignalInstance>>(types.specs.size());
				tokenArgSignals = new LinkedHashMap<String, List<Map<String, Map<String, SignalInstance>>>>();
			*****/
				
			Map<String, Map<ScorerData, SignalInstance>> tokenTriggerSignals = null;
			if (triggerSignals.size() <= i) {
				tokenTriggerSignals = new HashMap<String, Map<ScorerData, SignalInstance>>(types.specs.size());
				triggerSignals.add(tokenTriggerSignals);
			}
			else {
				tokenTriggerSignals = triggerSignals.get(i);
			}
//			Map<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>> tokenArgSignals = null;
//			if (argSignals.size() <= i) {
//				tokenArgSignals = new HashMap<Integer, List<Map<Integer, Map<ScorerData, SignalInstance>>>>();
//				argSignals.add(tokenArgSignals);
//			}
//			else {
//				tokenArgSignals = argSignals.get(i);
//			}
			
			//Integer specNum = -1;
			for (JCas spec : types.specs) {
				//specNum++;
				String specLabel = SpecAnnotator.getSpecLabel(spec);
				
				Map<ScorerData, SignalInstance> specSignals = null;
				List<Map<Integer, Map<ScorerData, SignalInstance>>> tokenArgSpecSignals = null;
				if (!tokenTriggerSignals.containsKey(specLabel)) {
					specSignals = new HashMap<ScorerData, SignalInstance>();
					tokenTriggerSignals.put(specLabel, specSignals);
//					tokenArgSpecSignals = new ArrayList<Map<Integer, Map<ScorerData, SignalInstance>>>();
//					tokenArgSignals.put(specNum, tokenArgSpecSignals);
				} 
				else {
					specSignals = tokenTriggerSignals.get(specLabel);
//					tokenArgSpecSignals = tokenArgSignals.get(specNum);
				}
				
				addTriggerSignals(spec, i, perceptron, specSignals, debug);
					
//				for(int k=0; k<eventArgCandidates.size(); k++) {
//					AceMention mention = eventArgCandidates.get(k);
//					//if(types.isEntityTypeEventCompatible(triggerLabel, mention.getType())) {
//					
//					Map<Integer, Map<ScorerData, SignalInstance>> tokenArgSpecEntitySignals = null;
//					if (tokenArgSpecSignals.size() <= k) {
//						tokenArgSpecEntitySignals = new HashMap<Integer, Map<ScorerData, SignalInstance>>();
//						tokenArgSpecSignals.add(tokenArgSpecEntitySignals);
//					}
//					else {
//						tokenArgSpecEntitySignals = tokenArgSpecSignals.get(k);
//					}
//	
//					Integer roleNum = -1;
//					for (Argument argument : SpecAnnotator.getSpecArguments(spec)) {
//						roleNum++;
//						String role = argument.getRole().getCoveredText();								
//						//if(types.isRoleCompatible(mention.getType(), triggerLabel, role)) {
//
//						Map<ScorerData, SignalInstance> roleSignals = null;
//						if (!tokenArgSpecEntitySignals.containsKey(role)) {
//							roleSignals = new HashMap<ScorerData, SignalInstance>();
//							tokenArgSpecEntitySignals.put(roleNum, roleSignals);
//						}
//						else {
//							roleSignals = tokenArgSpecEntitySignals.get(roleNum);
//						}
//						
//						//addArgumentSignals(spec, i, argument, mention, perceptron, roleSignals);
//					}
//				}
			}
		}
		//System.out.printf("%s Finished signals SentenceInstance %s.\n", Pipeline.detailedLog(), this.sentInstID);
	}
	
	public void addTriggerSignals(JCas spec, int i, Perceptron perceptron, Map<ScorerData, SignalInstance> specSignals, boolean debug) throws SignalMechanismException {
		for (SignalMechanism mechanism : perceptron.signalMechanisms) {
			
			///DEBUG
//			if (sentInstID.equals("5a") && docID.equals("CNN_CF_20030303.1900.00") && i==3) {
//				System.out.printf("\n\n\n\n\ngot it\n\n\n");
//			}
//			if (sentInstID.equals("5b") && docID.equals("CNN_CF_20030303.1900.00") && i==3) {
//				System.out.printf("\n\n\n\n\ngot it\n\n\n");
//			}
//			if (sentInstID.equals("5c") && docID.equals("CNN_CF_20030303.1900.00") && i==3) {
//				System.out.printf("\n\n\n\n\ngot it\n\n\n");
//			}
			///
			mechanism.scoreTrigger(specSignals, /*perceptron.triggerScorers,*/ spec, this, i, debug);
			
			// Good debug info for signals and scorers!
//			List<ScorerData> scorers = mechanism.scorers.get(SignalType.TRIGGER);
//			System.out.printf("%s finished mechanism %s with %s scorers: %s\n", Pipeline.detailedLog(),
//					mechanism.getClass().getSimpleName(), scorers.size(), scorers.toString().substring(0, 100) + "...");
		}
		//return result;
	}
	
	public void addArgumentSignals(JCas spec, int i, Argument argument, AceMention mention, Perceptron perceptron, Map<ScorerData, SignalInstance> roleSignals) throws SignalMechanismException {
		//This is currently all very silly.
		
//		LinkedHashMap<ScorerData, BigDecimal> scoredSignals;
//		for (SignalMechanism mechanism : perceptron.signalMechanisms) {
//			scoredSignals = mechanism.scoreArgument(roleSignals, spec, argument, this, i, mention);
//			for (Entry<ScorerData, BigDecimal> scoredSignal : scoredSignals.entrySet()) {
//				SignalInstance signal = new SignalInstance(scoredSignal.getKey().fullName, SignalType.ARGUMENT, scoredSignal.getValue());
//				roleSignals.put(scoredSignal.getKey(), signal);
//				//result.put(scoredSignal.getKey(), signal);
//				perceptron.argumentScorers.add(scoredSignal.getKey());
//				markSignalUpdate();
//			}
//		}
	}
	
	public void markSignalUpdate() {
		doc.signalsUpdated = true;
	}
}
