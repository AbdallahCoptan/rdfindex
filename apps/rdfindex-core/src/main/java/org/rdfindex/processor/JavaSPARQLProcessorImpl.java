package org.rdfindex.processor;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.rdfindex.dao.MetadataDAOImpl;
import org.rdfindex.dao.RDFIndexMetadataDAO;
import org.rdfindex.to.AggregatedTO;
import org.rdfindex.to.ComponentTO;
import org.rdfindex.to.DatasetStructureTO;
import org.rdfindex.to.IndexTO;
import org.rdfindex.to.IndicatorTO;
import org.rdfindex.to.ObservationTO;
import org.rdfindex.utils.PrettyPrinter;
import org.rdfindex.utils.RDFIndexUtils;
import org.rdfindex.utils.RDFIndexVocabulary;
import org.rdfindex.utils.SPARQLFetcherUtils;
import org.rdfindex.utils.SPARQLQueriesHelper;
import org.rdfindex.utils.SPARQLUtils;

import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.rdf.model.Model;

public class JavaSPARQLProcessorImpl  implements Processor{

	protected Logger logger = Logger.getLogger(JavaSPARQLProcessorImpl.class);
	protected RDFIndexMetadataDAO metadata;
	protected Model abox;
	protected Model tbox;
	
	public JavaSPARQLProcessorImpl(){
		
	}
	@Override
	public List<ObservationTO> run(Model tbox, Model abox) {
		this.abox = abox;
		this.tbox = tbox;
		List<ObservationTO> observations = new LinkedList<ObservationTO>();
		
		this.metadata = new MetadataDAOImpl(tbox, abox);
		List<IndexTO> indexes = this.metadata.getIndexMetadata();
		for(IndexTO index:indexes){
			List<ObservationTO> indexObservations = processIndex(index);
			observations.addAll(indexObservations);
		}
	
		return observations;
	}
	private List<ObservationTO> processIndex(IndexTO index) {
		List<ObservationTO> observations = new LinkedList<ObservationTO>();
		List<ComponentTO> components = index.getComponents();
		for(ComponentTO component:components){
			List<ObservationTO> componentObservations = processComponent(component);
			observations.addAll(componentObservations);
		}
	
		return observations;
	}
	private List<ObservationTO> processComponent(ComponentTO component) {
		List<ObservationTO> observations = new LinkedList<ObservationTO>();
		List<IndicatorTO> indicators = component.getIndicators();
		for(IndicatorTO indicator:indicators){
			List<ObservationTO> indicatorObservations = processIndicator(indicator);
			observations.addAll(indicatorObservations);
		}
		return observations;
	}
	
	private List<ObservationTO> processIndicator(IndicatorTO indicator) {
		List<ObservationTO> observations = new LinkedList<ObservationTO>();
		String sparqlQuery = createSPARQLQuery(indicator.getMetadata(),indicator.getAggregated());
		QuerySolution[] results = SPARQLUtils.executeSimpleSparql(abox, sparqlQuery);
		List<ObservationTO> newObservations = fetchNewObservations(indicator.getMetadata(),results);
		PrettyPrinter.prettyPrint(SPARQLQueriesHelper.observationsAsRDF(newObservations));
		return observations;
	}
	
	protected static List<ObservationTO> fetchNewObservations(DatasetStructureTO metadata, QuerySolution[] results) {
		List<ObservationTO> newObservations = new LinkedList<ObservationTO>();
		//A new observation will be created with the next metadata
		for(int i = 0; i<results.length;i++){
			ObservationTO observation = new ObservationTO();
			observation.setUri(createObservationUniqueID());
			observation.setUriDataset(metadata.getElement());
			observation.setMeasure(metadata.getMeasure());
			observation.setValue(SPARQLFetcherUtils.fetchStringValue(results[i], RDFIndexUtils.NEW_VALUE_VAR_SPARQL));//depends on metadata
			//Fetch dimensions
			Set<String> dimensions = metadata.getDimensions();
			int d = 0; //Maybe a table?
			for(String dimension:dimensions){
				String dimVar = "?dim"+(d++);
				String valueDimension = SPARQLFetcherUtils.fetchStringOrResource(results[i], dimVar);		
				//It is necessary to have the flag to know if it is a literal
				observation.getDimensions().put(dimension, valueDimension);
			}
			newObservations.add(observation);
		}
		return newObservations;
	}
	
	
	
	protected static String createSPARQLQuery(DatasetStructureTO metadata, AggregatedTO aggregated) {
		Set<String> dimensions = metadata.getDimensions();
		Set<String> partsOf = aggregated.getPartsOf();
		String measure = metadata.getMeasure();
		String operator = aggregated.getOperator();
		StringBuffer createDimensionsBGPs = new StringBuffer();
		StringBuffer dimensionVars = new StringBuffer();
		int i = 0; //Maybe a table?
		for(String dim:dimensions){
			String dimVar = "?dim"+(i++);
			dimensionVars.append(" "+dimVar);
			createDimensionsBGPs.append(
					SPARQLFetcherUtils.formatVar(RDFIndexUtils.OBSERVATION_VAR_SPARQL)+" "+
					SPARQLFetcherUtils.formatResource(dim)+" "+
					" "+dimVar+". "
			);
		}
		String sparqlQuery = SPARQLUtils.NS+" "+
		"SELECT "+dimensionVars+" "+formatFormula(operator)+" "+ 
		"WHERE{ " +
			SPARQLFetcherUtils.formatVar(RDFIndexUtils.OBSERVATION_VAR_SPARQL)+" "+				
				SPARQLFetcherUtils.formatResource(RDFIndexVocabulary.QB_DATASET.getURI())+" "+
						SPARQLFetcherUtils.formatVar(RDFIndexUtils.PART_VAR_SPARQL)+" . "+
			SPARQLFetcherUtils.createFilterPartsOf(partsOf)+
			SPARQLFetcherUtils.formatVar(RDFIndexUtils.OBSERVATION_VAR_SPARQL)+" "+
					SPARQLFetcherUtils.formatResource(measure)+" "+
						SPARQLFetcherUtils.formatVar(RDFIndexUtils.MEASURE_VAR_SPARQL)+" . "+
			createDimensionsBGPs+		
		"} GROUP BY"+dimensionVars;
		;
		return sparqlQuery;
	}



	//FIXME: extract mapping, what happen with the operation aggregator
	protected static String formatFormula(String operator) {
		System.out.println("OPERATOR "+operator);
		if (operator.equalsIgnoreCase("http://purl.org/rdfindex/ontology/Mean")){
			String function = "avg"+"("+SPARQLFetcherUtils.formatVar(RDFIndexUtils.MEASURE_VAR_SPARQL)+")";
			return "("+ function+" as "+SPARQLFetcherUtils.formatVar(RDFIndexUtils.NEW_VALUE_VAR_SPARQL)+")";
		}
		//Rename variable
		return "( min("+ SPARQLFetcherUtils.formatVar(RDFIndexUtils.MEASURE_VAR_SPARQL)+") as "+SPARQLFetcherUtils.formatVar(RDFIndexUtils.NEW_VALUE_VAR_SPARQL)+")";
	}
	
	//FIXME: in a distributed environment this does not ensure an unique id, a common repo should be used
	protected static String createObservationUniqueID() {
		return "http://purl.org/rdfindex/computation/o"+System.nanoTime(); 
	}
	
	
}