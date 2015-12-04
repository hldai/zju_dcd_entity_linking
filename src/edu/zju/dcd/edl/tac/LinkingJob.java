// author: DHL brnpoem@gmail.com

package edu.zju.dcd.edl.tac;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

import edu.zju.dcd.edl.cg.AliasDict;
import edu.zju.dcd.edl.cg.CandidatesRetriever;
import edu.zju.dcd.edl.config.ConfigUtils;
import edu.zju.dcd.edl.config.IniFile;
import edu.zju.dcd.edl.feature.FeatureLoader;
import edu.zju.dcd.edl.feature.TfIdfExtractor;
import edu.zju.dcd.edl.io.IOUtils;
import edu.zju.dcd.edl.linker.LinkerWithAliasDict;
import edu.zju.dcd.edl.linker.NaiveLinker;
import edu.zju.dcd.edl.linker.RandomLinker;
import edu.zju.dcd.edl.linker.SimpleLinker;
import edu.zju.dcd.edl.linker.SimpleNaiveLinker;
import edu.zju.dcd.edl.obj.Document;
import edu.zju.dcd.edl.obj.LinkingResult;
import edu.zju.dcd.edl.tac.CrossDocNilHandler;
import edu.zju.dcd.edl.utils.WidMidMapper;
import edu.zju.dcd.edl.wordvec.WordPredictor;

public class LinkingJob {
	public static void run(IniFile config) {
		IniFile.Section mainSect = config.getSection("main");
		String job = mainSect.getValue("job"), linkerName = mainSect
				.getValue("linker");
		System.out.println(linkerName);

		if (job.startsWith("link_with_linking_basis")) {
			if (linkerName.equals("naive")) {
				IniFile.Section sect = config.getSection(job);
				boolean useMid = sect.getIntValue("use_mid") == 1;
				
				String dstTrainingFile = config.getSection(job).getValue("dst_training_file");
				SimpleNaiveLinker simpleNaiveLinker = getSimpleNaiveLinker(config, dstTrainingFile, useMid);
				linkWithLinkingBasisFile(config, job, simpleNaiveLinker);
				simpleNaiveLinker.closeTrainingDataWriter();
			}
		} else if (job.equals("link_full")) {
			LinkerWithAliasDict linker = getFullLinker(config, linkerName);

			IniFile.Section querySect = config.getSection("query");
			if (querySect == null) {
				System.out.println("Couldn't find the query section.");
				return;
			}

			processQueryFile(querySect, linker);
			// TODO close
		} else if (job.startsWith("gen_linking_basis")) {
			CandidatesRetriever candidatesRetriever = ConfigUtils.getCandidateRetriever(config.getSection("dict"));
			IniFile.Section featSect = config.getSection("feature");
			FeatureLoader featureLoader = ConfigUtils.getFeatureLoader(featSect);
			TfIdfExtractor tfIdfExtractor = ConfigUtils.getTfIdfExtractor(featSect);
//			WordPredictor wordPredictor = ConfigUtils.getWordPredictor(config.getSection("word_predictor"));
			WordPredictor wordPredictor = null;
			WidMidMapper midWidMapper = ConfigUtils.getMidWidMapper(config.getSection("tac2014"));

			IniFile.Section sect = config.getSection(job);
			String queryFileName = sect.getValue("query_file"), srcDocPath = sect
					.getValue("src_doc_path"), dstFileName = sect
					.getValue("dst_file");

			if (job.equals("gen_linking_basis_doc")) {
				String docId = sect.getValue("doc_id");
				genLinkingBasisDocForDebug(candidatesRetriever, featureLoader, tfIdfExtractor, wordPredictor,
						midWidMapper,
						docId, queryFileName, srcDocPath, dstFileName);
			} else {
				genLinkingBasis(candidatesRetriever, featureLoader, tfIdfExtractor, wordPredictor,
						midWidMapper,
						queryFileName, srcDocPath, dstFileName);
			}
		}
	}
	
	private static void genLinkingBasisDocForDebug(CandidatesRetriever candidatesRetriever,
			FeatureLoader featureLoader, TfIdfExtractor tfIdfExtractor, WordPredictor wordPredictor,
			WidMidMapper midWidMapper, 
			String docId, String queryFile, String srcDocPath, String dstFileName) {
		LinkingBasisGen linkingBasisGen = new LinkingBasisGen(candidatesRetriever, featureLoader, tfIdfExtractor,
				wordPredictor, midWidMapper);
		Document[] documents = QueryReader.toDocuments(queryFile);
		BufferedWriter writer = IOUtils.getUTF8BufWriter(dstFileName, false);
		try {
			for (Document doc : documents) {
//				System.out.println(doc.docId + "\t" + docId + "\n");
				if (!doc.docId.equals(docId))
					continue;
				
				System.out.println(docId);
				doc.loadText(srcDocPath);
				LinkingBasisDoc linkingBasisDoc = linkingBasisGen.getLinkingBasisDoc(doc, 30);  // TODO
				doc.text = null;
				linkingBasisDoc.toFile(writer);
				break;
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static void genLinkingBasis(CandidatesRetriever candidatesRetriever,
			FeatureLoader featureLoader, TfIdfExtractor tfIdfExtractor, WordPredictor wordPredictor,
			WidMidMapper midWidMapper, 
			String queryFile, String srcDocPath, String dstFileName) {
		LinkingBasisGen linkingBasisGen = new LinkingBasisGen(candidatesRetriever, featureLoader, tfIdfExtractor,
				wordPredictor, midWidMapper);
		Document[] documents = QueryReader.toDocuments(queryFile);
		DataOutputStream dos = IOUtils.getBufferedDataOutputStream(dstFileName);
//		DataOutputStream dosTmp = IOUtils.getBufferedDataOutputStream("e:/el/");
		int mentionCnt = 0, docCnt = 0;
		try {
			for (Document doc : documents) {
				++docCnt;
				mentionCnt += doc.mentions.length;
				System.out.println("processing " + docCnt + " " + doc.docId + " " + doc.mentions.length);
				
				doc.loadText(srcDocPath);
				LinkingBasisDoc linkingBasisDoc = linkingBasisGen.getLinkingBasisDoc(doc, 40);  // TODO
				doc.text = null;
				linkingBasisDoc.toFile(dos);
				
//				if (docCnt == 3)
//					break;
			}
			dos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		System.out.println(mentionCnt + " mentions. " + docCnt + " documents.");
	}

	private static void linkWithLinkingBasisFile(IniFile config, String job,
			SimpleLinker simpleLinker) {
		IniFile.Section sect = config.getSection(job);
		String linkingBasisFileName = sect.getValue("linking_basis_file"), goldFileName = sect
				.getValue("gold_file"), resultFileName = sect
				.getValue("result_file"), errorListFileName = sect.getValue("error_list_file"),
				queryFileName = sect.getValue("query_file");
		
		boolean useMid = sect.getIntValue("use_mid") == 1;
		
		EidWidMapper eidWidMapper = null;

		if (!useMid) {
			String eidWidFileName = config.getSection("tac2014").getValue("eid_wid_file");
			eidWidMapper = new EidWidMapper(eidWidFileName);
		}

		DataInputStream dis = IOUtils
				.getBufferedDataInputStream(linkingBasisFileName);
		LinkingBasisDoc linkingBasisDoc = new LinkingBasisDoc();
		LinkedList<LinkingResult> resultList = new LinkedList<LinkingResult>();
		while (linkingBasisDoc.fromFile(dis)) {
			LinkingResult[] results = null;
			if (useMid) {
				results = simpleLinker.link(linkingBasisDoc);
			} else {
				results = simpleLinker.link14(linkingBasisDoc);
			}
			
			for (LinkingResult result : results) {
				// writer.write(result.queryId + "\t" + result.kbid + "\n");
				resultList.add(result);
			}
		}

		Collections.sort(resultList, new LinkingResult.ComparatorOnQueryId());
		CrossDocNilHandler.handle(resultList, queryFileName);
		writeLinkingResultsToFile(resultList, resultFileName, useMid);

		if (goldFileName != null)
			Scorer.score(goldFileName, resultFileName, queryFileName, eidWidMapper,
					errorListFileName);
	}

	private static void processQueryFile(IniFile.Section querySect,
			LinkerWithAliasDict linker) {
		String queryFileName = querySect.getValue("query_file"), srcDocPath = querySect
				.getValue("src_doc_path"), resultFileName = querySect
				.getValue("result_file"), goldFileName = querySect
				.getValue("gold_file");

		processQueryFile(linker, queryFileName, srcDocPath, resultFileName);
		Scorer.score(goldFileName, resultFileName, null, null, null);

		// System.out.println("Time used for retrieving candidates: "
		// + dict.getRetrieveTime() / 1000.0);
		// System.out.println("Time used for retrieving features: "
		// + npl.getRetrievePopularityTime() / 1000.0);
	}

	public static void processQueryFile(LinkerWithAliasDict linker,
			String queryFileName, String srcDocPath, String resultFileName) {
		Document[] documents = QueryReader.toDocuments(queryFileName);
		LinkedList<LinkingResult> allResults = new LinkedList<LinkingResult>();
		for (Document doc : documents) {
			doc.loadText(srcDocPath);
			LinkingResult[] results = linker.link(doc);
			for (LinkingResult result : results) {
				allResults.add(result);
			}
			doc.text = null;
		}
		
		Collections.sort(allResults, new LinkingResult.ComparatorOnQueryId());
		writeLinkingResultsToFile(allResults, resultFileName, true);
	}

	private static LinkerWithAliasDict getFullLinker(IniFile config,
			String linkerName) {
		IniFile.Section tac2014Sect = config.getSection("tac2014"), dictSect = config
				.getSection("dict");

		AliasDict dict = ConfigUtils.getAliasDict(dictSect);
		MidToEidMapper mteMapper = ConfigUtils.getMidToEidMapper(tac2014Sect);

		if (linkerName.equals("random")) {
			return new RandomLinker(dict, mteMapper);
		} else if (linkerName.equals("naive")) {
			IniFile.Section featSect = config.getSection("feature");
			TfIdfExtractor tfIdfExtractor = ConfigUtils
					.getTfIdfExtractor(featSect);
			FeatureLoader featureLoader = ConfigUtils
					.getFeatureLoader(featSect);

			return new NaiveLinker(dict, tfIdfExtractor, featureLoader,
					mteMapper);
		}
		return null;
	}

	private static SimpleNaiveLinker getSimpleNaiveLinker(IniFile config,
			String dstTrainingFileName, boolean useMid) {
		MidToEidMapper mteMapper = null;
		if (!useMid) {
			IniFile.Section tac2014Sect = config.getSection("tac2014");
			mteMapper = ConfigUtils.getMidToEidMapper(tac2014Sect);
		}
		
		IniFile.Section dictSect = config.getSection("dict");
		String filterMidsFileName = dictSect.getValue("filter_mids_file");
		MidFilter midFilter = null;
		if (filterMidsFileName != null) {
			midFilter = new MidFilter(filterMidsFileName);
		} else {
			System.out.println("Filter mids file is null.");
		}
		
		return new SimpleNaiveLinker(mteMapper, midFilter, dstTrainingFileName);
	}
	
	private static void writeLinkingResultsToFile(LinkedList<LinkingResult> results, String resultFileName,
			boolean useMid) {
		BufferedWriter writer = IOUtils.getUTF8BufWriter(resultFileName, false);
		try {
			for (LinkingResult result : results) {
				if (result.kbid.startsWith("NIL"))
					writer.write(result.queryId + "\t" + result.kbid + "\t" + result.confidence + "\n");
				else {
					if (useMid)
						writer.write(result.queryId + "\tm." + result.kbid + "\t" + result.confidence + "\n");
					else
						writer.write(result.queryId + "\t" + result.kbid + "\t" + result.confidence + "\n");
				}
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
