package edu.harvard.hms.dbmi.avillach.hpds.processing;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

//import org.apache.commons.math3.stat.inference.ChiSquareTest;
//import org.apache.commons.math3.stat.inference.TTest;
import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Filter.FloatFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.Query.VariantInfoFilter;
import edu.harvard.hms.dbmi.avillach.hpds.exception.NotEnoughMemoryException;
import edu.harvard.hms.dbmi.avillach.hpds.exception.TooManyVariantsException;

public abstract class AbstractProcessor {

	public AbstractProcessor() throws ClassNotFoundException, FileNotFoundException, IOException {
		store = initializeCache(); 
		Object[] metadata = loadMetadata();
		metaStore = (TreeMap<String, ColumnMeta>) metadata[0];
		allIds = (TreeSet<Integer>) metadata[1];
		infoStoreColumns = Arrays.stream(new File("/opt/local/hpds/all/").list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
				.map((String filename)->{return filename.split("_")[0];}).collect(Collectors.toList());
	}

	private static final String HOMOZYGOUS_VARIANT = "1/1";

	private static final String HETEROZYGOUS_VARIANT = "0/1";

	private static final String HOMOZYGOUS_REFERENCE = "0/0";

	private static Logger log = Logger.getLogger(AbstractProcessor.class);

	protected static String ID_CUBE_NAME;

	static {
		CACHE_SIZE = Integer.parseInt(System.getProperty("CACHE_SIZE"));
		ID_BATCH_SIZE = Integer.parseInt(System.getProperty("ID_BATCH_SIZE"));
		ID_CUBE_NAME = System.getProperty("ID_CUBE_NAME");
	}

	protected static int ID_BATCH_SIZE;

	protected static int CACHE_SIZE;

	public static List<String> infoStoreColumns;

	protected static HashMap<String, FileBackedByteIndexedInfoStore> infoStores;

	protected LoadingCache<String, PhenoCube<?>> store;

	protected static VariantStore variantStore;

	protected static TreeMap<String, ColumnMeta> metaStore;

	protected TreeSet<Integer> allIds;


	//	private GeneLibrary geneLibrary = new GeneLibrary();

	protected Object[] loadMetadata() {
		try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream("/opt/local/phenocube/columnMeta.javabin"));){
			TreeMap<String, ColumnMeta> metastore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
			TreeMap<String, ColumnMeta> metastoreScrubbed = new TreeMap<String, ColumnMeta>();
			for(Entry<String,ColumnMeta> entry : metastore.entrySet()) {
				metastoreScrubbed.put(entry.getKey().replaceAll("\\ufffd",""), entry.getValue());
			}
			Set<Integer> allIds = (TreeSet<Integer>) objectInputStream.readObject();
			return new Object[] {metastoreScrubbed, allIds};
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not load metastore");
		} 
	}

	protected Set<Integer> applyBooleanLogic(ArrayList<Set<Integer>> filteredIdSets) {
		Set<Integer>[] ids = new Set[] {filteredIdSets.get(0)};
		filteredIdSets.forEach((keySet)->{
			ids[0] = Sets.intersection(ids[0], keySet);
		});
		return ids[0];
	}
//
//	protected Map<String, Double> variantsOfInterestForSubset(String geneName, BigInteger caseMask, double pValueCutoff) throws IOException{
//		TreeSet<String> nonsynonymous_SNVs = new TreeSet<>(Arrays.asList(infoStores.get("UCG").allValues.get("nonsynonymous_SNV")));
//		TreeSet<String> variantsInGene = new TreeSet<>(Arrays.asList(infoStores.get("GN").allValues.get(geneName)));
//		TreeSet<String> nonsynVariantsInGene = new TreeSet<String>(Sets.intersection(variantsInGene, nonsynonymous_SNVs));
//
//		HashMap<String, Double> interestingVariants = new HashMap<>();
//
//		nonsynVariantsInGene.stream().forEach((variantSpec)->{
//			VariantMasks masks;
//			try {
//				masks = variantStore.getMasks(variantSpec);
//			} catch (IOException e) {
//				throw new RuntimeException(e);
//			}
//			BigInteger controlMask = flipMask(caseMask);
//			BigInteger variantAlleleMask = masks.heterozygousMask.or(masks.homozygousMask);
//			BigInteger referenceAlleleMask = flipMask(variantAlleleMask);
//			Double value = new ChiSquareTest().chiSquare(new long[][] {
//				{variantAlleleMask.and(caseMask).bitCount()-4, variantAlleleMask.and(controlMask).bitCount()-4},
//				{referenceAlleleMask.and(caseMask).bitCount()-4, referenceAlleleMask.and(controlMask).bitCount()-4}
//			});
//			if(value < pValueCutoff) {
//				interestingVariants.put(variantSpec, value);
//			}
//		});
//		return interestingVariants;
//	}

	private BigInteger flipMask(BigInteger caseMask) {
		for(int x = 2;x<caseMask.bitLength()-2;x++) {
			caseMask = caseMask.flipBit(x);
		}
		return caseMask;
	}

	protected ArrayList<Set<Integer>> idSetsForEachFilter(Query query) throws TooManyVariantsException {
		ArrayList<Set<Integer>> filteredIdSets = new ArrayList<Set<Integer>>();
		if(query.requiredFields != null && !query.requiredFields.isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.requiredFields.parallelStream().map(path->{
				return new TreeSet<Integer>(getCube(path).keyBasedIndex()) ;
			}).collect(Collectors.toSet()))); 
		}
		if(query.numericFilters != null && !query.numericFilters.isEmpty()) {
			filteredIdSets.addAll((Set<TreeSet<Integer>>)(query.numericFilters.keySet().parallelStream().map((String key)->{
				FloatFilter FloatFilter = query.numericFilters.get(key);
				return (TreeSet<Integer>)(getCube(key).getKeysForRange(FloatFilter.getMin(), FloatFilter.getMax()));
			}).collect(Collectors.toSet())));
		}

		/* VARIANT INFO FILTER HANDLING IS MESSY */
		if(query.variantInfoFilters != null && !query.variantInfoFilters.isEmpty()) {
			for(VariantInfoFilter filter : query.variantInfoFilters){
				ArrayList<Set<String>> variantSets = new ArrayList<>();
				// Add variant sets for each filter
				if(filter.categoryVariantInfoFilters != null && !filter.categoryVariantInfoFilters.isEmpty()) {
					filter.categoryVariantInfoFilters.forEach((String column, String[] values)->{
						Arrays.sort(values);
						FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);
						List<String> infoKeys = infoStore.allValues.keys().stream().filter((String key)->{
							int insertionIndex = Arrays.binarySearch(values, key);
							return insertionIndex > -1 && insertionIndex < values.length;
						}).collect(Collectors.toList());
						for(String key : infoKeys) {
							try {
								variantSets.add(new TreeSet<String>(Arrays.asList(infoStore.allValues.get(key))));
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					});
				}
				if(filter.numericVariantInfoFilters != null && !filter.numericVariantInfoFilters.isEmpty()) {
					filter.numericVariantInfoFilters.forEach((String column, FloatFilter floatFilter)->{
						FileBackedByteIndexedInfoStore infoStore = getInfoStore(column);
						floatFilter.getMax();
						Range<Float> filterRange = Range.closed(floatFilter.getMin(), floatFilter.getMax());
						List<String> valuesInRange = infoStore.continuousValueIndex.getValuesInRange(filterRange);
						TreeSet<String> variants = new TreeSet<String>();
						for(String value : valuesInRange) {
							try {
								for(String variantName : infoStore.allValues.get(value)) {
									System.out.println(variantName);
								}
								variants.addAll(Arrays.asList(infoStore.allValues.get(value)));
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
						variantSets.add(new TreeSet<String>(variants));
					});
				}
				if(!variantSets.isEmpty()) {
					// INTERSECT all the variant sets.
					Set<String> intersectionOfInfoFilters = variantSets.get(0);
					for(Set<String> variantSet : variantSets) {
						intersectionOfInfoFilters = Sets.intersection(intersectionOfInfoFilters, variantSet);
					}
					// add filteredIdSet for patients who have matching variants, heterozygous or homozygous for now.
					log.info("Number of matching variant sets : " + variantSets.size());
					IntSummaryStatistics stats = variantSets.stream().collect(Collectors.summarizingInt(set->set.size()));
					log.info("Number of matching variants for all sets : " + stats);
					log.info("Number of matching variants for intersection of sets : " + intersectionOfInfoFilters.size());
					if(intersectionOfInfoFilters.size() > 100000) {
						log.info("Too many variants included in query, will not attempt to process");
						throw new TooManyVariantsException();
					}else if(!intersectionOfInfoFilters.isEmpty()) {
						try {
							VariantMasks masks;
							BigInteger heteroMask = variantStore.emptyBitmask();
							BigInteger homoMask = variantStore.emptyBitmask();
							BigInteger matchingPatients = variantStore.emptyBitmask();
							for(String variant : intersectionOfInfoFilters){
								masks = variantStore.getMasks(variant);
								if(masks != null) {
									heteroMask = masks.heterozygousMask == null ? variantStore.emptyBitmask() : masks.heterozygousMask;
									homoMask = masks.homozygousMask == null ? variantStore.emptyBitmask() : masks.homozygousMask;
									BigInteger orMasks = heteroMask.or(homoMask);
									matchingPatients = matchingPatients.or(orMasks);								
								}
							}
							Set<Integer> ids = new TreeSet<Integer>();
							String bitmaskString = matchingPatients.toString(2);
							log.info("or'd masks : " + bitmaskString);
							PhenoCube<String> patientIdCube = (PhenoCube<String>) store.get(ID_CUBE_NAME);
							for(int x = 2;x < bitmaskString.length()-2;x++) {
								if('1'==bitmaskString.charAt(x)) {
									// Minor hack here to deal with Baylor not sticking to one file naming convention
									String patientId = variantStore.getPatientIds()[x-2].split("_")[0];
									try {
										ids.add(patientIdCube.getKeysForValue(patientId).iterator().next());
									}catch(NullPointerException e) {
										System.out.println("Could not find id for patient " + patientId);
									}
								}
							}
							filteredIdSets.add(ids);
						} catch (IOException e) {
							log.error(e);
						} catch (ExecutionException e) {
							log.error(e);
						}					
					}else {
						log.error("No matches found for info filters.");
						filteredIdSets.add(new TreeSet<>());
					}
				}else {
					log.error("No info filters included in query.");
				}
			}

		}
		/* END OF VARIANT INFO FILTER HANDLING */

		if(query.categoryFilters != null && !query.categoryFilters.isEmpty()) {
			Set<Set<Integer>> idsThatMatchFilters = (Set<Set<Integer>>)query.categoryFilters.keySet().parallelStream().map((String key)->{
				Set<Integer> ids = new TreeSet<Integer>();
				if(pathIsVariantSpec(key)) {
					ArrayList<BigInteger> variantBitmasks = new ArrayList<>();
					Arrays.stream(query.categoryFilters.get(key)).forEach((zygosity) -> {
						String variantName = key.replaceAll(",\\d/\\d$", "");
						System.out.println("looking up masks : " + key + " to " + variantName);
						VariantMasks masks;
						try {
							masks = variantStore.getMasks(variantName);
							if(masks!=null) {
								if(zygosity.equals(HOMOZYGOUS_REFERENCE)) {
									BigInteger indiscriminateVariantBitmap = null;
									if(masks.heterozygousMask == null && masks.homozygousMask != null) {
										indiscriminateVariantBitmap = masks.homozygousMask;
									}else if(masks.homozygousMask == null && masks.heterozygousMask != null) {
										indiscriminateVariantBitmap = masks.heterozygousMask;
									}else if(masks.homozygousMask != null && masks.heterozygousMask != null) {
										indiscriminateVariantBitmap = masks.heterozygousMask.or(masks.homozygousMask);
									}
									for(int x = 2;x<indiscriminateVariantBitmap.bitLength()-2;x++) {
										indiscriminateVariantBitmap = indiscriminateVariantBitmap.flipBit(x);
									}
									variantBitmasks.add(indiscriminateVariantBitmap);
								} else if(masks.heterozygousMask != null && zygosity.equals(HETEROZYGOUS_VARIANT)) {
									BigInteger heterozygousVariantBitmap = masks.heterozygousMask;
									variantBitmasks.add(heterozygousVariantBitmap);							
								}else if(masks.homozygousMask != null && zygosity.equals(HOMOZYGOUS_VARIANT)) {
									BigInteger homozygousVariantBitmap = masks.homozygousMask;
									variantBitmasks.add(homozygousVariantBitmap);
								}else if(zygosity.equals("")) {
									if(masks.heterozygousMask == null && masks.homozygousMask != null) {
										variantBitmasks.add(masks.homozygousMask);
									}else if(masks.homozygousMask == null && masks.heterozygousMask != null) {
										variantBitmasks.add(masks.heterozygousMask);
									}else if(masks.homozygousMask != null && masks.heterozygousMask != null) {
										BigInteger indiscriminateVariantBitmap = masks.heterozygousMask.or(masks.homozygousMask);
										variantBitmasks.add(indiscriminateVariantBitmap);
									}
								}
							} else {
								variantBitmasks.add(variantStore.emptyBitmask());
							}
						} catch (IOException e) {
							log.error(e);
						}
					});
					if( ! variantBitmasks.isEmpty()) {
						BigInteger bitmask = variantBitmasks.get(0);
						if(variantBitmasks.size()>1) {
							for(int x = 1;x<variantBitmasks.size();x++) {
								bitmask = bitmask.or(variantBitmasks.get(x));
							}
						}
						String bitmaskString = bitmask.toString(2);
						System.out.println("or'd masks : " + bitmaskString);
						PhenoCube<String> idCube;
						try {
							idCube = (PhenoCube<String>) store.get(ID_CUBE_NAME);
							for(int x = 2;x < bitmaskString.length()-2;x++) {
								if('1'==bitmaskString.charAt(x)) {
									// Minor hack here to deal with Baylor not sticking to one file naming convention
									String patientId = variantStore.getPatientIds()[x-2].split("_")[0];
									try{
										ids.add(idCube.getKeysForValue(patientId).iterator().next());
									}catch(NullPointerException e) {
										log.error(ID_CUBE_NAME + " has no value for patientId : " + patientId);
									}
								}
							}
						} catch (ExecutionException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
//				} else if(pathIsGeneName(key)) {
					//					try {
					//						List<VCFPerPatientVariantMasks> matchingMasks = 
					//								variantStore.getMasksForRangesOfChromosome(
					//										geneLibrary.getChromosomeForGene(key), 
					//										geneLibrary.offsetsForGene(key),
					//										geneLibrary.rangeSetForGene(key));
					//						System.out.println("Found " + matchingMasks.size() + " masks for variant " + key);
					//						BigInteger matchingPatients = variantStore.emptyBitmask();
					//						for(String zygosity : query.categoryFilters.get(key)) {
					//							if(zygosity.equals(HETEROZYGOUS_VARIANT)) {
					//								for(VCFPerPatientVariantMasks masks : matchingMasks) {
					//									if(masks!=null) {
					//										if(masks.heterozygousMask != null) {
					//											//											String bitmaskString = masks.heterozygousMask.toString(2);
					//											//											System.out.println("heterozygousMask : " + bitmaskString);
					//											matchingPatients = matchingPatients.or(masks.heterozygousMask);
					//										}
					//									}
					//								}
					//							}else if(zygosity.equals(HOMOZYGOUS_VARIANT)) {
					//								for(VCFPerPatientVariantMasks masks : matchingMasks) {
					//									if(masks!=null) {
					//										if(masks.homozygousMask != null) {
					//											//											String bitmaskString = masks.homozygousMask.toString(2);
					//											//											System.out.println("homozygousMask : " + bitmaskString);
					//											matchingPatients = matchingPatients.or(masks.homozygousMask);
					//										}
					//									}
					//								}					
					//							}else if(zygosity.equals("")) {
					//								for(VCFPerPatientVariantMasks masks : matchingMasks) {
					//									if(masks!=null) {
					//										if(masks.homozygousMask != null) {
					//											//											String bitmaskString = masks.homozygousMask.toString(2);
					//											//											System.out.println("homozygousMask : " + bitmaskString);
					//											matchingPatients = matchingPatients.or(masks.homozygousMask);
					//										}
					//										if(masks.heterozygousMask != null) {
					//											//											String bitmaskString = masks.heterozygousMask.toString(2);
					//											//											System.out.println("heterozygousMask : " + bitmaskString);
					//											matchingPatients = matchingPatients.or(masks.heterozygousMask);
					//										}
					//									}
					//								}	
					//							}
					//						}
					//						String bitmaskString = matchingPatients.toString(2);
					//						System.out.println("or'd masks : " + bitmaskString);
					//						PhenoCube idCube = store.get(ID_CUBE_NAME);
					//						for(int x = 2;x < bitmaskString.length()-2;x++) {
					//							if('1'==bitmaskString.charAt(x)) {
					//								String patientId = variantStore.getPatientIds()[x-2];
					//								int id = -1;
					//								for(KeyAndValue<String> ids : idCube.sortedByValue()) {
					//									if(patientId.equalsIgnoreCase(ids.getValue())) {
					//										id = ids.getKey();
					//									}
					//								}
					//								ids.add(id);
					//							}
					//						}
					//					} catch (IOException | ExecutionException e) {
					//						log.error(e);
					//					} 
				} else {
					String[] categoryFilter = query.categoryFilters.get(key);
					for(String category : categoryFilter) {
						ids.addAll(getCube(key).getKeysForValue(category));
					}
				}
				return ids;
			}).collect(Collectors.toSet());
			filteredIdSets.addAll(idsThatMatchFilters);
		}
		for(Set set : filteredIdSets) {
			System.out.println("filtered set : " + set.stream().map((integer) -> {return integer.toString() + " ";}).collect(Collectors.joining()));
		}
		return filteredIdSets;
	}

	public FileBackedByteIndexedInfoStore getInfoStore(String column) {
		return infoStores.get(column);
	}
//
//	private boolean pathIsGeneName(String key) {
//		return new GeneLibrary().geneNameSearch(key).size()==1;
//	}

	public boolean pathIsVariantSpec(String key) {
		return key.matches("rs[0-9]+.*") || key.matches("[0-9]+,[0-9\\.]+,.*");
	}

	protected ArrayList<Integer> useResidentCubesFirst(List<String> paths, int columnCount) {
		int x;
		TreeSet<String> pathSet = new TreeSet<String>(paths);
		Set<String> residentKeys = Sets.intersection(pathSet, store.asMap().keySet());

		ArrayList<Integer> columnIndex = new ArrayList<Integer>();

		residentKeys.forEach(key ->{
			columnIndex.add(paths.indexOf(key) + 1);
		});

		Sets.difference(pathSet, residentKeys).forEach(key->{
			columnIndex.add(paths.indexOf(key) + 1);
		});

		for(x = 1;x < columnCount;x++) {
			columnIndex.add(x);
		}
		return columnIndex;
	}

	protected LoadingCache<String, PhenoCube<?>> initializeCache() throws ClassNotFoundException, FileNotFoundException, IOException {
		if(new File("/opt/local/hpds/all/variantStore.javabin").exists()) {
			this.variantStore = (VariantStore) new ObjectInputStream(new GZIPInputStream(new FileInputStream("/opt/local/hpds/all/variantStore.javabin"))).readObject();
			variantStore.open();			
		}
		return CacheBuilder.newBuilder()
				.maximumSize(CACHE_SIZE)
				.build(
						new CacheLoader<String, PhenoCube<?>>() {
							public PhenoCube<?> load(String key) throws Exception {
								try(RandomAccessFile allObservationsStore = new RandomAccessFile("/opt/local/phenocube/allObservationsStore.javabin", "r");){
									ColumnMeta columnMeta = metaStore.get(key);
									if(columnMeta != null) {
										allObservationsStore.seek(columnMeta.getAllObservationsOffset());
										int length = (int) (columnMeta.getAllObservationsLength() - columnMeta.getAllObservationsOffset());
										byte[] buffer = new byte[length];
										allObservationsStore.read(buffer);
										allObservationsStore.close();
										ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)));
										PhenoCube<?> ret = (PhenoCube<?>)inStream.readObject();
										inStream.close();
										return ret;																		
									}else {
										System.out.println("ColumnMeta not found for : [" + key + "]");
										return null;
									}
								}
							}
						});
	}

	public void loadAllDataFiles() {
		if(Crypto.hasKey()) {
			List<String> cubes = new ArrayList<String>(metaStore.keySet());
			for(int x = 0;x<Math.min(metaStore.size(), CACHE_SIZE);x++){
				try {
					if(metaStore.get(cubes.get(x)).getObservationCount() == 0){
						log.info("Rejecting : " + cubes.get(x) + " because it has no entries.");
					}else {
						store.get(cubes.get(x));
						log.info("loaded: " + cubes.get(x));					
					}
				} catch (ExecutionException e) {
					log.error(e);
				}

			}
			infoStores = new HashMap<>();
			Arrays.stream(new File("/opt/local/hpds/all/").list((file, filename)->{return filename.endsWith("infoStore.javabin");}))
			.forEach((String filename)->{
				try (
						FileInputStream fis = new FileInputStream("/opt/local/hpds/all/" + filename);
						GZIPInputStream gis = new GZIPInputStream(fis);
						ObjectInputStream ois = new ObjectInputStream(gis)
						){
					FileBackedByteIndexedInfoStore infoStore = (FileBackedByteIndexedInfoStore) ois.readObject();
					infoStores.put(filename.replace("_infoStore.javabin", ""), infoStore);	
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ClassNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
		}
	}

	@SuppressWarnings("rawtypes")
	protected PhenoCube getCube(String path) {
		try { 
			return store.get(path);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public TreeMap<String, ColumnMeta> getDictionary() {
		return metaStore;
	}

	public abstract void runQuery(Query query, AsyncResult asyncResult) throws NotEnoughMemoryException, TooManyVariantsException;

}
