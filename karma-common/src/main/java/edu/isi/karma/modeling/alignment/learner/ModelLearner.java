/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/

package edu.isi.karma.modeling.alignment.learner;

import java.io.File;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.WeightedMultigraph;
import org.python.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.config.ModelingConfiguration;
import edu.isi.karma.modeling.alignment.GraphBuilder;
import edu.isi.karma.modeling.alignment.GraphBuilderTopK;
import edu.isi.karma.modeling.alignment.GraphUtil;
import edu.isi.karma.modeling.alignment.GraphVizUtil;
import edu.isi.karma.modeling.alignment.LinkIdFactory;
import edu.isi.karma.modeling.alignment.ModelEvaluation;
import edu.isi.karma.modeling.alignment.NodeIdFactory;
import edu.isi.karma.modeling.alignment.SemanticModel;
import edu.isi.karma.modeling.alignment.SteinerTree;
import edu.isi.karma.modeling.alignment.TreePostProcess;
import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.modeling.research.Params;
import edu.isi.karma.rep.alignment.ClassInstanceLink;
import edu.isi.karma.rep.alignment.ColumnNode;
import edu.isi.karma.rep.alignment.DataPropertyLink;
import edu.isi.karma.rep.alignment.DefaultLink;
import edu.isi.karma.rep.alignment.InternalNode;
import edu.isi.karma.rep.alignment.Label;
import edu.isi.karma.rep.alignment.LabeledLink;
import edu.isi.karma.rep.alignment.Node;
import edu.isi.karma.rep.alignment.SemanticType;
import edu.isi.karma.rep.alignment.SemanticType.Origin;
import edu.isi.karma.util.RandomGUID;
import edu.isi.karma.webserver.ServletContextParameterMap;
import edu.isi.karma.webserver.ServletContextParameterMap.ContextParameter;

public class ModelLearner {

	private static Logger logger = LoggerFactory.getLogger(ModelLearner.class);
	private OntologyManager ontologyManager = null;
	private GraphBuilder graphBuilder = null;
	private NodeIdFactory nodeIdFactory = null; 
	private List<Node> steinerNodes = null;
	private SemanticModel semanticModel = null;
//	private long lastUpdateTimeOfGraph;
	private boolean useKarmaAlignmentGraph;

	private static final int NUM_SEMANTIC_TYPES = 4;

	public ModelLearner(OntologyManager ontologyManager, 
			List<Node> steinerNodes) {
		if (ontologyManager == null || 
				steinerNodes == null || 
				steinerNodes.isEmpty()) {
			logger.error("cannot instanciate model learner!");
			return;
		}
		this.ontologyManager = ontologyManager;
		this.steinerNodes = steinerNodes;
		this.useKarmaAlignmentGraph = true;
	}

	public ModelLearner(GraphBuilder graphBuilder, 
			List<Node> steinerNodes) {
		if (graphBuilder == null || 
				steinerNodes == null || 
				steinerNodes.isEmpty()) {
			logger.error("cannot instanciate model learner!");
			return;
		}
		this.steinerNodes = steinerNodes;
		this.graphBuilder = graphBuilder;
		this.nodeIdFactory = this.graphBuilder.getNodeIdFactory();
		this.ontologyManager = this.graphBuilder.getOntologyManager();
		this.useKarmaAlignmentGraph = false;
	}

	public SemanticModel getModel() {
		if (this.semanticModel == null)
			try {
				this.learn();
			} catch (Exception e) {
				logger.error("error in learing the semantic model for the source " + this.semanticModel != null ? this.semanticModel.getId() : "");
				e.printStackTrace();
			}

		return this.semanticModel;
	}

	public void learn() throws Exception {

		if (this.useKarmaAlignmentGraph) {
			this.graphBuilder = 
					ModelLearningGraph.getInstance(ontologyManager, ModelLearningGraphType.Compact).getGraphBuilder();
			this.nodeIdFactory = this.graphBuilder.getNodeIdFactory();
		}
		
		this.graphBuilder = cloneGraphBuilder(this.graphBuilder); // create a copy of the graph builder
		List<SortableSemanticModel> hypothesisList = this.hypothesize(true, NUM_SEMANTIC_TYPES);
		if (hypothesisList != null && !hypothesisList.isEmpty()) {
			SortableSemanticModel m = hypothesisList.get(0);
			this.semanticModel = new SemanticModel(m);
		} else {
			this.semanticModel = null;
		}
	}

	private GraphBuilder cloneGraphBuilder(GraphBuilder graphBuilder) {

		GraphBuilder clonedGraphBuilder = null;
		if (graphBuilder == null || graphBuilder.getGraph() == null) {
			clonedGraphBuilder = new GraphBuilderTopK(this.ontologyManager, this.nodeIdFactory, false);
		} else {
			clonedGraphBuilder = new GraphBuilderTopK(this.ontologyManager, graphBuilder.getGraph());
		}
		return clonedGraphBuilder;
	}

//	private boolean isGraphUpToDate() {
//
//		if (this.lastUpdateTimeOfGraph < this.modelLearningGraph.getLastUpdateTime())
//			return false;
//		
//		return true;
//	}

	public List<SortableSemanticModel> hypothesize(boolean useCorrectTypes, int numberOfCandidates) throws Exception {

		List<SortableSemanticModel> sortableSemanticModels = new ArrayList<SortableSemanticModel>();
		Set<Node> addedNodes = new HashSet<Node>(); //They should be deleted from the graph after computing the semantic models

		List<ColumnNode> columnNodes = new LinkedList<ColumnNode>();
		for (Node n : steinerNodes)
			if (n instanceof ColumnNode)
				columnNodes.add((ColumnNode)n);
		
		logger.info("finding candidate steiner sets ... ");
		CandidateSteinerSets candidateSteinerSets = getCandidateSteinerSets(steinerNodes, useCorrectTypes, numberOfCandidates, addedNodes);

		if (candidateSteinerSets == null || 
				candidateSteinerSets.getSteinerSets() == null || 
				candidateSteinerSets.getSteinerSets().isEmpty()) {
			logger.error("there is no candidate set of steiner nodes.");
			
			DirectedWeightedMultigraph<Node, LabeledLink> tree = 
					new DirectedWeightedMultigraph<Node, LabeledLink>(LabeledLink.class);
			
			for (Node n : steinerNodes)
				tree.addVertex(n);
			
			SemanticModel sm = new SemanticModel(new RandomGUID().toString(), tree);
			SortableSemanticModel sortableSemanticModel = new SortableSemanticModel(sm, null);
			sortableSemanticModels.add(sortableSemanticModel);
			return sortableSemanticModels;
		}
		
		logger.info("graph nodes: " + this.graphBuilder.getGraph().vertexSet().size());
		logger.info("graph links: " + this.graphBuilder.getGraph().edgeSet().size());

		logger.info("number of steiner sets: " + candidateSteinerSets.numberOfCandidateSets());

//		logger.info("updating weights according to training data ...");
//		long start = System.currentTimeMillis();
//		this.updateWeights();
//		long updateWightsElapsedTimeMillis = System.currentTimeMillis() - start;
//		logger.info("time to update weights: " + (updateWightsElapsedTimeMillis/1000F));
		
		logger.info("computing steiner trees ...");
		int number = 1;
		for (SteinerNodes sn : candidateSteinerSets.getSteinerSets()) {
			if (sn == null) continue;
			logger.debug("computing steiner tree for steiner nodes set " + number + " ...");
			logger.debug(sn.getScoreDetailsString());
			number++;
//			logger.info("START ...");
			
			List<DirectedWeightedMultigraph<Node, LabeledLink>> topKSteinerTrees;
			if (this.graphBuilder instanceof GraphBuilderTopK)
				topKSteinerTrees =  ((GraphBuilderTopK)this.graphBuilder).getTopKSteinerTrees(sn.getNodes(), ModelingConfiguration.getMaxCandidateModels());
			else {
				topKSteinerTrees = new LinkedList<DirectedWeightedMultigraph<Node, LabeledLink>>();
				SteinerTree steinerTree = new SteinerTree(
						new AsUndirectedGraph<Node, DefaultLink>(this.graphBuilder.getGraph()), Lists.newLinkedList(sn.getNodes()));
				WeightedMultigraph<Node, DefaultLink> t = steinerTree.getDefaultSteinerTree();
				TreePostProcess treePostProcess = new TreePostProcess(this.graphBuilder, t);
				if (treePostProcess.getTree() != null)
					topKSteinerTrees.add(treePostProcess.getTree());
			}
			
//			System.out.println(GraphUtil.labeledGraphToString(treePostProcess.getTree()));
			
//			logger.info("END ...");

			for (DirectedWeightedMultigraph<Node, LabeledLink> tree: topKSteinerTrees) {
				if (tree != null) {
//					System.out.println();
					SemanticModel sm = new SemanticModel(new RandomGUID().toString(), 
							tree,
							columnNodes,
							sn.getMappingToSourceColumns()
							);
					SortableSemanticModel sortableSemanticModel = 
							new SortableSemanticModel(sm, sn);
					sortableSemanticModels.add(sortableSemanticModel);
					
//					System.out.println(GraphUtil.labeledGraphToString(sm.getGraph()));
				}
			}
			if (number == ModelingConfiguration.getMaxCandidateModels())
				break;

		}

		Collections.sort(sortableSemanticModels);
		int count = Math.min(sortableSemanticModels.size(), ModelingConfiguration.getMaxCandidateModels());
		logger.info("results are ready ...");
		sortableSemanticModels.get(0).print();
		return sortableSemanticModels.subList(0, count);

//		List<SortableSemanticModel> uniqueModels = new ArrayList<SortableSemanticModel>();
//		SortableSemanticModel current, previous;
//		if (sortableSemanticModels != null) {
//			if (sortableSemanticModels.size() > 0)
//				uniqueModels.add(sortableSemanticModels.get(0));
//			for (int i = 1; i < sortableSemanticModels.size(); i++) {
//				current = sortableSemanticModels.get(i);
//				previous = sortableSemanticModels.get(i - 1);
//				if (current.getScore() == previous.getScore() && current.getCost() == previous.getCost())
//					continue;
//				uniqueModels.add(current);
//			}
//		}
//		
//		logger.info("results are ready ...");
//		return uniqueModels;

	}

//	private DirectedWeightedMultigraph<Node, LabeledLink> computeSteinerTree(Set<Node> steinerNodes) {
//
//		if (steinerNodes == null || steinerNodes.size() == 0) {
//			logger.error("There is no steiner node.");
//			return null;
//		}
//
//		//		System.out.println(steinerNodes.size());
//		List<Node> steinerNodeList = new ArrayList<Node>(steinerNodes); 
//
//		long start = System.currentTimeMillis();
//		UndirectedGraph<Node, DefaultLink> undirectedGraph = new AsUndirectedGraph<Node, DefaultLink>(this.graphBuilder.getGraph());
//
//		logger.debug("computing steiner tree ...");
//		SteinerTree steinerTree = new SteinerTree(undirectedGraph, steinerNodeList);
//		DirectedWeightedMultigraph<Node, LabeledLink> tree = new TreePostProcess(this.graphBuilder, steinerTree.getDefaultSteinerTree(), null, false).getTree();
//		//(DirectedWeightedMultigraph<Node, LabeledLink>)GraphUtil.asDirectedGraph(steinerTree.getDefaultSteinerTree());
//
//		logger.debug(GraphUtil.labeledGraphToString(tree));
//
//		long steinerTreeElapsedTimeMillis = System.currentTimeMillis() - start;
//		logger.debug("total number of nodes in steiner tree: " + tree.vertexSet().size());
//		logger.debug("total number of edges in steiner tree: " + tree.edgeSet().size());
//		logger.debug("time to compute steiner tree: " + (steinerTreeElapsedTimeMillis/1000F));
//
//		return tree;
//
//		//		long finalTreeElapsedTimeMillis = System.currentTimeMillis() - steinerTreeElapsedTimeMillis;
//		//		DirectedWeightedMultigraph<Node, Link> finalTree = buildOutputTree(tree);
//		//		logger.info("time to build final tree: " + (finalTreeElapsedTimeMillis/1000F));
//
//		//		GraphUtil.printGraph(finalTree);
//		//		return finalTree; 
//
//	}

	private CandidateSteinerSets getCandidateSteinerSets(List<Node> steinerNodes, boolean useCorrectTypes, int numberOfCandidates, Set<Node> addedNodes) {

		if (steinerNodes == null || steinerNodes.isEmpty())
			return null;

		int maxNumberOfSteinerNodes = steinerNodes.size() * 2;
		CandidateSteinerSets candidateSteinerSets = new CandidateSteinerSets(maxNumberOfSteinerNodes);

		if (addedNodes == null) 
			addedNodes = new HashSet<Node>();

		Set<SemanticTypeMapping> tempSemanticTypeMappings;
		HashMap<ColumnNode, List<SemanticType>> columnSemanticTypes = new HashMap<ColumnNode, List<SemanticType>>();
		HashMap<String, Integer> semanticTypesCount = new HashMap<String, Integer>();
		List<SemanticType> candidateSemanticTypes;
		String domainUri = "", propertyUri = "";

		for (Node n : steinerNodes) {

			ColumnNode cn = null;
			if (n instanceof ColumnNode)
				cn = (ColumnNode)n;
			else
				continue;
				
			candidateSemanticTypes = getCandidateSemanticTypes(cn, useCorrectTypes, numberOfCandidates);
			columnSemanticTypes.put(cn, candidateSemanticTypes);

			for (SemanticType semanticType: candidateSemanticTypes) {

				if (semanticType == null || 
						semanticType.getDomain() == null ||
						semanticType.getType() == null) continue;

				domainUri = semanticType.getDomain().getUri();
				propertyUri = semanticType.getType().getUri();

				Integer count = semanticTypesCount.get(domainUri + propertyUri);
				if (count == null) semanticTypesCount.put(domainUri + propertyUri, 1);
				else semanticTypesCount.put(domainUri + propertyUri, count.intValue() + 1);
			}
		}

		long numOfMappings = 1;
		
		for (Node n : steinerNodes) {

			if (n instanceof InternalNode) 
				continue;
			
			ColumnNode cn = null;
			if (n instanceof ColumnNode)
				cn = (ColumnNode)n;
			else
				continue;
			
			candidateSemanticTypes = columnSemanticTypes.get(n);
			if (candidateSemanticTypes == null) continue;

			logger.info("===== Column: " + cn.getColumnName());

			Set<SemanticTypeMapping> semanticTypeMappings = new HashSet<SemanticTypeMapping>();
			for (SemanticType semanticType: candidateSemanticTypes) {

				logger.info("\t" + semanticType.getConfidenceScore() + " :" + semanticType.getModelLabelString());

				if (semanticType == null || 
						semanticType.getDomain() == null ||
						semanticType.getType() == null) continue;

				domainUri = semanticType.getDomain().getUri();
				propertyUri = semanticType.getType().getUri();
				Integer countOfSemanticType = semanticTypesCount.get(domainUri + propertyUri);
				logger.debug("count of semantic type: " +  countOfSemanticType);

				
				if (cn.getDomainNode() != null) {
					SemanticTypeMapping mp = new SemanticTypeMapping(cn, semanticType, cn.getDomainNode(), cn.getDomainLink(), cn);
					semanticTypeMappings.add(mp);
				} else {

					tempSemanticTypeMappings = findSemanticTypeInGraph(cn, semanticType, semanticTypesCount, addedNodes);
					logger.debug("number of matches for semantic type: " +  
						 + (tempSemanticTypeMappings == null ? 0 : tempSemanticTypeMappings.size()));
	
					if (tempSemanticTypeMappings != null) 
						semanticTypeMappings.addAll(tempSemanticTypeMappings);
	
					int countOfMatches = tempSemanticTypeMappings == null ? 0 : tempSemanticTypeMappings.size();
	//				if (countOfMatches < countOfSemanticType) 
					if (countOfMatches == 0) // No struct in graph is matched with the semantic type, we add a new struct to the graph
					{
						SemanticTypeMapping mp = addSemanticTypeStruct(cn, semanticType, addedNodes);
						if (mp != null)
							semanticTypeMappings.add(mp);
					}
				}
			}
			//			System.out.println("number of matches for column " + n.getColumnName() + 
			//					": " + (semanticTypeMappings == null ? 0 : semanticTypeMappings.size()));
			logger.debug("number of matches for column " + cn.getColumnName() + 
					": " + (semanticTypeMappings == null ? 0 : semanticTypeMappings.size()));
			numOfMappings *= (semanticTypeMappings == null || semanticTypeMappings.isEmpty() ? 1 : semanticTypeMappings.size());

			logger.debug("number of candidate steiner sets before update: " + candidateSteinerSets.getSteinerSets().size());
			candidateSteinerSets.updateSteinerSets(semanticTypeMappings);
			logger.debug("number of candidate steiner sets after update: " + candidateSteinerSets.getSteinerSets().size());
		}

		for (Node n : steinerNodes) {
			if (n instanceof InternalNode) {
				candidateSteinerSets.updateSteinerSets((InternalNode)n);
			}
		}
		
		//		System.out.println("number of possible mappings: " + numOfMappings);
		logger.info("number of possible mappings: " + numOfMappings);

		return candidateSteinerSets;
	}

	private Set<SemanticTypeMapping> findSemanticTypeInGraph(ColumnNode sourceColumn, SemanticType semanticType, 
			HashMap<String, Integer> semanticTypesCount, Set<Node> addedNodes) {

		logger.debug("finding matches for semantic type in the graph ... ");

		if (addedNodes == null)
			addedNodes = new HashSet<Node>();

		Set<SemanticTypeMapping> mappings = new HashSet<SemanticTypeMapping>();

		if (semanticType == null) {
			logger.error("semantic type is null.");
			return mappings;

		}
		if (semanticType.getDomain() == null) {
			logger.error("semantic type does not have any domain");
			return mappings;
		}

		if (semanticType.getType() == null) {
			logger.error("semantic type does not have any link");
			return mappings;
		}

		String domainUri = semanticType.getDomain().getUri();
		String propertyUri = semanticType.getType().getUri();
		Double confidence = semanticType.getConfidenceScore();
		Origin origin = semanticType.getOrigin();

		Integer countOfSemanticType = semanticTypesCount.get(domainUri + propertyUri);
		if (countOfSemanticType == null) {
			logger.error("count of semantic type should not be null or zero");
			return mappings;
		}

		if (domainUri == null || domainUri.isEmpty()) {
			logger.error("semantic type does not have any domain");
			return mappings;
		}

		if (propertyUri == null || propertyUri.isEmpty()) {
			logger.error("semantic type does not have any link");
			return mappings;
		}

		logger.debug("semantic type: " + domainUri + "|" + propertyUri + "|" + confidence + "|" + origin);

		// add dataproperty to existing classes if sl is a data node mapping
		//		Set<Node> foundInternalNodes = new HashSet<Node>();
		Set<SemanticTypeMapping> semanticTypeMatches = this.graphBuilder.getSemanticTypeMatches().get(domainUri + propertyUri);
		if (semanticTypeMatches != null) {
			for (SemanticTypeMapping stm : semanticTypeMatches) {

				SemanticTypeMapping mp = 
						new SemanticTypeMapping(sourceColumn, semanticType, stm.getSource(), stm.getLink(), stm.getTarget());
				mappings.add(mp);
				//				foundInternalNodes.add(stm.getSource());
			}
		}

		logger.debug("adding data property to the found internal nodes ...");

		Integer count;
		boolean allowMultipleSamePropertiesPerNode = ModelingConfiguration.isMultipleSamePropertyPerNode();
		Set<Node> nodesWithSameUriOfDomain = this.graphBuilder.getUriToNodesMap().get(domainUri);
		if (nodesWithSameUriOfDomain != null) { 
			for (Node source : nodesWithSameUriOfDomain) {
				count = this.graphBuilder.getNodeDataPropertyCount().get(source.getId() + propertyUri);

				if (count != null) {
					if (allowMultipleSamePropertiesPerNode) {
						if (count >= countOfSemanticType.intValue())
							continue;
					} else {
						if (count >= 1) 
							continue;
					}
				}


				String nodeId = new RandomGUID().toString();
				ColumnNode target = new ColumnNode(nodeId, nodeId, sourceColumn.getColumnName(), null);
				if (!this.graphBuilder.addNode(target)) continue;;
				addedNodes.add(target);

				String linkId = LinkIdFactory.getLinkId(propertyUri, source.getId(), target.getId());	
				LabeledLink link = new DataPropertyLink(linkId, new Label(propertyUri), false);
				if (!this.graphBuilder.addLink(source, target, link)) continue;;

				SemanticTypeMapping mp = new SemanticTypeMapping(sourceColumn, semanticType, (InternalNode)source, link, target);
				mappings.add(mp);
			}
		}

		return mappings;
	}

	private SemanticTypeMapping addSemanticTypeStruct(ColumnNode sourceColumn, SemanticType semanticType, Set<Node> addedNodes) {

		logger.debug("adding semantic type to the graph ... ");

		if (addedNodes == null) 
			addedNodes = new HashSet<Node>();

		if (semanticType == null) {
			logger.error("semantic type is null.");
			return null;

		}
		if (semanticType.getDomain() == null) {
			logger.error("semantic type does not have any domain");
			return null;
		}

		if (semanticType.getType() == null) {
			logger.error("semantic type does not have any link");
			return null;
		}

		String domainUri = semanticType.getDomain().getUri();
		String propertyUri = semanticType.getType().getUri();
		Double confidence = semanticType.getConfidenceScore();
		Origin origin = semanticType.getOrigin();

		if (domainUri == null || domainUri.isEmpty()) {
			logger.error("semantic type does not have any domain");
			return null;
		}

		if (propertyUri == null || propertyUri.isEmpty()) {
			logger.error("semantic type does not have any link");
			return null;
		}

		logger.debug("semantic type: " + domainUri + "|" + propertyUri + "|" + confidence + "|" + origin);

		InternalNode source = null;
		String nodeId;

		nodeId = nodeIdFactory.getNodeId(domainUri);
		source = new InternalNode(nodeId, new Label(domainUri));
		if (!this.graphBuilder.addNodeAndUpdate(source, addedNodes)) return null;

		nodeId = new RandomGUID().toString();
		ColumnNode target = new ColumnNode(nodeId, nodeId, sourceColumn.getColumnName(), null);
		if (!this.graphBuilder.addNode(target)) return null;
		addedNodes.add(target);

		String linkId = LinkIdFactory.getLinkId(propertyUri, source.getId(), target.getId());	
		LabeledLink link;
		if (propertyUri.equalsIgnoreCase(ClassInstanceLink.getFixedLabel().getUri()))
			link = new ClassInstanceLink(linkId);
		else {
			Label label = this.ontologyManager.getUriLabel(propertyUri);
			link = new DataPropertyLink(linkId, label, false);
		}
		if (!this.graphBuilder.addLink(source, target, link)) return null;

		SemanticTypeMapping mappingStruct = new SemanticTypeMapping(sourceColumn, semanticType, source, link, target);

		return mappingStruct;
	}

	private List<SemanticType> getCandidateSemanticTypes(ColumnNode n, boolean useCorrectType, int numberOfCandidates) {

		if (n == null)
			return null;

		List<SemanticType> types = new ArrayList<>();

		SemanticType userSelectedType = n.getUserSelectedSemanticType();
		if (useCorrectType && userSelectedType != null) {
			double probability = 1.0;
			SemanticType newType = new SemanticType(
					userSelectedType.getHNodeId(),
					userSelectedType.getType(),
					userSelectedType.getDomain(),
					userSelectedType.getOrigin(),
					probability,
					false
					);
			types.add(newType);
		} else {
			List<SemanticType> suggestions = n.getTopKSuggestions(numberOfCandidates);
			if (suggestions != null) {
				for (SemanticType st : suggestions) {
					if (useCorrectType && userSelectedType != null &&
							st.getModelLabelString().equalsIgnoreCase(userSelectedType.getModelLabelString()))
						continue;
					types.add(st);
				}
			}
		}

		return types;

	}

//	private void updateWeights() {
//
//		List<DefaultLink> oldLinks = new ArrayList<DefaultLink>();
//
//		List<Node> sources = new ArrayList<Node>();
//		List<Node> targets = new ArrayList<Node>();
//		List<LabeledLink> newLinks = new ArrayList<LabeledLink>();
//		List<Double> weights = new ArrayList<Double>();
//
//		HashMap<String, LinkFrequency> sourceTargetLinkFrequency = 
//				new HashMap<String, LinkFrequency>();
//
//		LinkFrequency lf1, lf2;
//
//		String key, key1, key2;
//		String linkUri;
//		for (DefaultLink link : this.graphBuilder.getGraph().edgeSet()) {
//			linkUri = link.getUri();
//			if (!linkUri.equalsIgnoreCase(Uris.DEFAULT_LINK_URI)) {
//				if (link.getTarget() instanceof InternalNode && !linkUri.equalsIgnoreCase(Uris.RDFS_SUBCLASS_URI)) {
//					key = "domain:" + link.getSource().getLabel().getUri() + ",link:" + linkUri + ",range:" + link.getTarget().getLabel().getUri();
//					Integer count = this.graphBuilder.getLinkCountMap().get(key);
//					if (count != null)
//						this.graphBuilder.changeLinkWeight(link, ModelingParams.PATTERN_LINK_WEIGHT - ((double)count / (double)this.graphBuilder.getNumberOfModelLinks()) );
//				}
//				continue;
//			}
//
//			key1 = link.getSource().getLabel().getUri() + 
//					link.getTarget().getLabel().getUri();
//			key2 = link.getTarget().getLabel().getUri() + 
//					link.getSource().getLabel().getUri();
//
//			lf1 = sourceTargetLinkFrequency.get(key1);
//			if (lf1 == null) {
//				lf1 = this.graphBuilder.getMoreFrequentLinkBetweenNodes(link.getSource().getLabel().getUri(), link.getTarget().getLabel().getUri());
//				sourceTargetLinkFrequency.put(key1, lf1);
//			}
//
//			lf2 = sourceTargetLinkFrequency.get(key2);
//			if (lf2 == null) {
//				lf2 = this.graphBuilder.getMoreFrequentLinkBetweenNodes(link.getTarget().getLabel().getUri(), link.getSource().getLabel().getUri());
//				sourceTargetLinkFrequency.put(key2, lf2);
//			}
//
//			int c = lf1.compareTo(lf2);
//			String id = null;
//			if (c > 0) {
//				sources.add(link.getSource());
//				targets.add(link.getTarget());
//
//				id = LinkIdFactory.getLinkId(lf1.getLinkUri(), link.getSource().getId(), link.getTarget().getId());
//				if (link instanceof ObjectPropertyLink)
//					newLinks.add(new ObjectPropertyLink(id, new Label(lf1.getLinkUri()), ((ObjectPropertyLink) link).getObjectPropertyType()));
//				else if (link instanceof SubClassLink)
//					newLinks.add(new SubClassLink(id));
//
//				weights.add(lf1.getWeight());
//			} else if (c < 0) {
//				sources.add(link.getTarget());
//				targets.add(link.getSource());
//
//				id = LinkIdFactory.getLinkId(lf2.getLinkUri(), link.getSource().getId(), link.getTarget().getId());
//				if (link instanceof ObjectPropertyLink)
//					newLinks.add(new ObjectPropertyLink(id, new Label(lf2.getLinkUri()), ((ObjectPropertyLink) link).getObjectPropertyType()));
//				else if (link instanceof SubClassLink)
//					newLinks.add(new SubClassLink(id));
//
//				weights.add(lf2.getWeight());
//			} else
//				continue;
//
//			oldLinks.add(link);
//		}
//
//		for (DefaultLink link : oldLinks)
//			this.graphBuilder.getGraph().removeEdge(link);
//
//		LabeledLink newLink;
//		for (int i = 0; i < newLinks.size(); i++) {
//			newLink = newLinks.get(i);
//			this.graphBuilder.addLink(sources.get(i), targets.get(i), newLink);
//			this.graphBuilder.changeLinkWeight(newLink, weights.get(i));
//		}
//	}

	private static double roundDecimals(double d, int k) {
		String format = "";
		for (int i = 0; i < k; i++) format += "#";
        DecimalFormat DForm = new DecimalFormat("#." + format);
        return Double.valueOf(DForm.format(d));
	}

	@SuppressWarnings("unused")
	private static void getStatistics1(List<SemanticModel> semanticModels) {
		for (int i = 0; i < semanticModels.size(); i++) {
			SemanticModel source = semanticModels.get(i);
			int attributeCount = source.getColumnNodes().size();
			int nodeCount = source.getGraph().vertexSet().size();
			int linkCount = source.getGraph().edgeSet().size();
			int datanodeCount = 0;
			int classNodeCount = 0;
			for (Node n : source.getGraph().vertexSet()) {
				if (n instanceof InternalNode) classNodeCount++;
				if (n instanceof ColumnNode) datanodeCount++;
			}
			System.out.println(attributeCount + "\t" + nodeCount + "\t" + linkCount + "\t" + classNodeCount + "\t" + datanodeCount);
			
			List<ColumnNode> columnNodes = source.getColumnNodes();
			getStatistics2(columnNodes);

		}
	}
	
	private static void getStatistics2(List<ColumnNode> columnNodes) {

		if (columnNodes == null)
			return;

		int numberOfAttributesWhoseTypeIsFirstCRFType = 0;
		int numberOfAttributesWhoseTypeIsInCRFTypes = 0;
		for (ColumnNode cn : columnNodes) {
			SemanticType userSelectedType = cn.getUserSelectedSemanticType();
			List<SemanticType> top4Suggestions = cn.getTopKSuggestions(4);

			for (int i = 0; i < top4Suggestions.size(); i++) {
				SemanticType st = top4Suggestions.get(i);
				if (userSelectedType != null &&
						st.getModelLabelString().equalsIgnoreCase(userSelectedType.getModelLabelString())) {
					if (i == 0) numberOfAttributesWhoseTypeIsFirstCRFType ++;
					numberOfAttributesWhoseTypeIsInCRFTypes ++;
					break;
				} 
			}

		}

		System.out.println(numberOfAttributesWhoseTypeIsInCRFTypes + "\t" + numberOfAttributesWhoseTypeIsFirstCRFType);
//		System.out.println(columnNodes.size() + "\t" + numberOfAttributesWhoseTypeIsInCRFTypes + "\t" + numberOfAttributesWhoseTypeIsFirstCRFType);

//		System.out.println("totalNumberOfAttributes: " + columnNodes.size());
//		System.out.println("numberOfAttributesWhoseTypeIsInCRFTypes: " + numberOfAttributesWhoseTypeIsInCRFTypes);
//		System.out.println("numberOfAttributesWhoseTypeIsFirstCRFType:" + numberOfAttributesWhoseTypeIsFirstCRFType);
	}

	public static void test() throws Exception {

		ServletContextParameterMap.setParameterValue(ContextParameter.USER_CONFIG_DIRECTORY, "/Users/mohsen/karma/config");

		//		String inputPath = Params.INPUT_DIR;
		String outputPath = Params.OUTPUT_DIR;
		String graphPath = Params.GRAPHS_DIR;

		//		List<SemanticModel> semanticModels = ModelReader.importSemanticModels(inputPath);
		List<SemanticModel> semanticModels = 
				ModelReader.importSemanticModelsFromJsonFiles(Params.MODEL_DIR, Params.MODEL_MAIN_FILE_EXT);

		//		ModelEvaluation me2 = semanticModels.get(20).evaluate(semanticModels.get(20));
		//		System.out.println(me2.getPrecision() + "--" + me2.getRecall());
		//		if (true)
		//			return;

		List<SemanticModel> trainingData = new ArrayList<SemanticModel>();

		OntologyManager ontologyManager = new OntologyManager();
		File ff = new File(Params.ONTOLOGY_DIR);
		File[] files = ff.listFiles();
		for (File f : files) {
			ontologyManager.doImport(f, "UTF-8");
		}
		ontologyManager.updateCache();  

//		getStatistics1(semanticModels);
//
//		if (true)
//			return;

		ModelLearningGraph modelLearningGraph = null;
		
		ModelLearner modelLearner;

		boolean iterativeEvaluation = true;
		boolean useCorrectType = false;
		int numberOfCRFCandidates = 4;
		int numberOfKnownModels;
		String filePath = Params.RESULTS_DIR;
		String filename = "results,k=" + numberOfCRFCandidates + ".csv"; 
		PrintWriter resultFile = new PrintWriter(new File(filePath + filename));

		StringBuffer[] resultsArray = new StringBuffer[semanticModels.size() + 2];
		for (int i = 0; i < resultsArray.length; i++) {
			resultsArray[i] = new StringBuffer();
		}

		for (int i = 0; i < semanticModels.size(); i++) {
//		for (int i = 0; i <= 10; i++) {
//		int i = 0; {

			resultFile.flush();
			int newSourceIndex = i;
			SemanticModel newSource = semanticModels.get(newSourceIndex);

			logger.info("======================================================");
			logger.info(newSource.getName() + "(#attributes:" + newSource.getColumnNodes().size() + ")");
			System.out.println(newSource.getName() + "(#attributes:" + newSource.getColumnNodes().size() + ")");
			logger.info("======================================================");

			if (!iterativeEvaluation)
				numberOfKnownModels = semanticModels.size() - 1;
			else
				numberOfKnownModels = 0;

			if (resultsArray[0].length() > 0)	resultsArray[0].append(" \t ");			
			resultsArray[0].append(newSource.getName() + "(" + newSource.getColumnNodes().size() + ")" + "\t" + " " + "\t" + " ");
			if (resultsArray[1].length() > 0)	resultsArray[1].append(" \t ");			
			resultsArray[1].append("p \t r \t t");

//			numberOfKnownModels = 2;
			while (numberOfKnownModels <= semanticModels.size() - 1) 
			{

				trainingData.clear();

				int j = 0, count = 0;
				while (count < numberOfKnownModels) {
					if (j != newSourceIndex) {
						trainingData.add(semanticModels.get(j));
						count++;
					}
					j++;
				}

				modelLearningGraph = (ModelLearningGraphCompact)ModelLearningGraph.getEmptyInstance(ontologyManager, ModelLearningGraphType.Compact);
				
				
				SemanticModel correctModel = newSource;
				List<ColumnNode> columnNodes = correctModel.getColumnNodes();
				//				if (useCorrectType && numberOfCRFCandidates > 1)
				//					updateCrfSemanticTypesForResearchEvaluation(columnNodes);

				List<Node> steinerNodes = new LinkedList<Node>(columnNodes);
				modelLearner = new ModelLearner(ontologyManager, steinerNodes);
				long start = System.currentTimeMillis();

				String graphName = !iterativeEvaluation?
						graphPath + semanticModels.get(newSourceIndex).getName() + Params.GRAPH_FILE_EXT : 
							graphPath + semanticModels.get(newSourceIndex).getName() + ".knownModels=" + numberOfKnownModels + Params.GRAPH_FILE_EXT;

				if (new File(graphName).exists()) {
					// read graph from file
					try {
						logger.info("loading the graph ...");
						DirectedWeightedMultigraph<Node, DefaultLink> graph = GraphUtil.importJson(graphName);
						modelLearner.graphBuilder = new GraphBuilderTopK(ontologyManager, graph);
						modelLearner.nodeIdFactory = modelLearner.graphBuilder.getNodeIdFactory();
					} catch (Exception e) {
						e.printStackTrace();
					}
				} else 
				{
					logger.info("building the graph ...");
					for (SemanticModel sm : trainingData)
//						modelLearningGraph.addModel(sm);
						modelLearningGraph.addModelAndUpdate(sm);
					modelLearner.graphBuilder = modelLearningGraph.getGraphBuilder();
					modelLearner.nodeIdFactory = modelLearner.graphBuilder.getNodeIdFactory();
					// save graph to file
					try {
//						GraphUtil.exportJson(modelLearningGraph.getGraphBuilder().getGraph(), graphName);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				
//				GraphVizUtil.exportJGraphToGraphviz(modelLearner.graphBuilder.getGraph(), "test", true, false, true, graphName + ".dot");
				List<SortableSemanticModel> hypothesisList = modelLearner.hypothesize(useCorrectType, numberOfCRFCandidates);

				long elapsedTimeMillis = System.currentTimeMillis() - start;
				float elapsedTimeSec = elapsedTimeMillis/1000F;

				List<SortableSemanticModel> topHypotheses = null;
				if (hypothesisList != null) {
					topHypotheses = hypothesisList.size() > ModelingConfiguration.getMaxCandidateModels() ? 
							hypothesisList.subList(0, ModelingConfiguration.getMaxCandidateModels()) : 
								hypothesisList;
				}

				Map<String, SemanticModel> models = 
						new TreeMap<String, SemanticModel>();

				// export to json
				//				if (topHypotheses != null)
				//					for (int k = 0; k < topHypotheses.size() && k < 3; k++) {
				//						
				//						String fileExt = null;
				//						if (k == 0) fileExt = Params.MODEL_RANK1_FILE_EXT;
				//						else if (k == 1) fileExt = Params.MODEL_RANK2_FILE_EXT;
				//						else if (k == 2) fileExt = Params.MODEL_RANK3_FILE_EXT;
				//						SortableSemanticModel m = topHypotheses.get(k);
				//						new SemanticModel(m).writeJson(Params.MODEL_DIR + 
				//								newSource.getName() + fileExt);
				//						
				//					}

				ModelEvaluation me;
				models.put("1-correct model", correctModel);
				if (topHypotheses != null)
					for (int k = 0; k < topHypotheses.size(); k++) {

						SortableSemanticModel m = topHypotheses.get(k);

						me = m.evaluate(correctModel);

						String label = "candidate" + k + 
								m.getSteinerNodes().getScoreDetailsString() +
								"cost:" + roundDecimals(m.getCost(), 6) + 
								//								"-distance:" + me.getDistance() + 
								"-precision:" + me.getPrecision() + 
								"-recall:" + me.getRecall();

						models.put(label, m);

						if (k == 0) { // first rank model
							System.out.println("number of known models: " + numberOfKnownModels +
									", precision: " + me.getPrecision() + 
									", recall: " + me.getRecall() + 
									", time: " + elapsedTimeSec);
							logger.info("number of known models: " + numberOfKnownModels +
									", precision: " + me.getPrecision() + 
									", recall: " + me.getRecall() + 
									", time: " + elapsedTimeSec);
//							resultFile.println("number of known models \t precision \t recall");
//							resultFile.println(numberOfKnownModels + "\t" + me.getPrecision() + "\t" + me.getRecall());
							String s = me.getPrecision() + "\t" + me.getRecall() + "\t" + elapsedTimeSec;
							if (resultsArray[numberOfKnownModels + 2].length() > 0) 
								resultsArray[numberOfKnownModels + 2].append(" \t ");
							resultsArray[numberOfKnownModels + 2].append(s);

//							resultFile.println(me.getPrecision() + "\t" + me.getRecall() + "\t" + elapsedTimeSec);
						}
					}

				String outName = !iterativeEvaluation?
						outputPath + semanticModels.get(newSourceIndex).getName() + Params.GRAPHVIS_OUT_DETAILS_FILE_EXT : 
							outputPath + semanticModels.get(newSourceIndex).getName() + ".knownModels=" + numberOfKnownModels + Params.GRAPHVIS_OUT_DETAILS_FILE_EXT;

				//	if (!iterativeEvaluation) {
				GraphVizUtil.exportSemanticModelsToGraphviz(
						models, 
						newSource.getName(),
						outName,
						true,
						false);
				//				}

				numberOfKnownModels ++;

			}

		//	resultFile.println("=======================================================");
		}
		for (StringBuffer s : resultsArray)
			resultFile.println(s.toString());

		resultFile.close();
	}
	
	public static void main(String[] args) throws Exception {

		test();

	}

}
