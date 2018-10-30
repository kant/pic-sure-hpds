package edu.harvard.hms.dbmi.avillach.pheno;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

import edu.harvard.hms.dbmi.avillach.pheno.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.pheno.data.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.pheno.data.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.pheno.data.PhenoCube;

import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CSVLoader {

	private static Logger log = Logger.getLogger(CSVLoader.class);

	private static final int PATIENT_NUM = 0;

	private static final int CONCEPT_PATH = 1;

	private static final int NUMERIC_VALUE = 2;

	private static final int TEXT_VALUE = 3;

	private static HashMap<String, byte[]> compressedPhenoCubes = new HashMap<>();

	private static RandomAccessFile allObservationsStore;

	private static TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();

	private static LoadingCache<String, PhenoCube> store = CacheBuilder.newBuilder()
			.maximumSize(2000)
			.removalListener(new RemovalListener<String, PhenoCube>() {

				@Override
				public void onRemoval(RemovalNotification<String, PhenoCube> arg0) {
					log.info("removing " + arg0.getKey());
					complete(arg0.getValue());
					try {
						ColumnMeta columnMeta = metadataMap.get(arg0.getKey());
						columnMeta.setAllObservationsOffset(allObservationsStore.getFilePointer());
						columnMeta.setObservationCount(arg0.getValue().sortedByKey().length);
						if(columnMeta.isCategorical()) {
							columnMeta.setCategoryValues(new ArrayList<String>());
							columnMeta.getCategoryValues().addAll(new TreeSet<String>(arg0.getValue().keyBasedArray()));
						} else {
							List<Float> map = (List<Float>) arg0.getValue().keyBasedArray().stream().map((value)->{return (Float) value;}).collect(Collectors.toList());
							float min = Float.MAX_VALUE;
							float max = Float.MIN_VALUE;
							for(float f : map) {
								min = Float.min(min, f);
								max = Float.max(max, f);
							}
							columnMeta.setMin(min);
							columnMeta.setMax(max);
						}
						ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
						try {

							ObjectOutputStream out = new ObjectOutputStream(byteStream);
							out.writeObject(arg0.getValue());
							out.flush();
							out.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
						allObservationsStore.write(Crypto.encryptData(byteStream.toByteArray()));
						columnMeta.setAllObservationsLength(allObservationsStore.getFilePointer());
						compressedPhenoCubes.put(arg0.getKey(), byteStream.toByteArray());
					} catch (IOException e1) {
						e1.printStackTrace();
					}
				}

				private <V extends Comparable<V>> void complete(PhenoCube<V> cube) {
					ArrayList<KeyAndValue<V>> entryList = new ArrayList<KeyAndValue<V>>(
							cube.loadingMap.entrySet().stream().map((entry)->{
								return new KeyAndValue<V>(entry.getKey(), entry.getValue());
							}).collect(Collectors.toList()));

					List<KeyAndValue<V>> sortedByKey = entryList.stream()
							.sorted(Comparator.comparing(KeyAndValue<V>::getKey))
							.collect(Collectors.toList());
					cube.setSortedByKey(sortedByKey.toArray(new KeyAndValue[0]));

					if(cube.isStringType()) {
						TreeMap<V, List<Integer>> categoryMap = new TreeMap<>();
						for(KeyAndValue<V> entry : cube.sortedByValue()) {
							if(!categoryMap.containsKey(entry.getValue())) {
								categoryMap.put(entry.getValue(), new LinkedList<Integer>());
							}
							categoryMap.get(entry.getValue()).add(entry.getKey());
						}
						TreeMap<V, TreeSet<Integer>> categorySetMap = new TreeMap<>();
						categoryMap.entrySet().stream().forEach((entry)->{
							categorySetMap.put(entry.getKey(), new TreeSet<Integer>(entry.getValue()));
						});
						cube.setCategoryMap(categorySetMap);
					}

				}
			})
			.build(
					new CacheLoader<String, PhenoCube>() {
						public PhenoCube load(String key) throws Exception {
							log.info(key);
							byte[] bytes = compressedPhenoCubes.get(key);
							if(bytes == null) return null;
							ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(bytes));
							PhenoCube ret = (PhenoCube)inStream.readObject();
							inStream.close();
							return ret;
						}
					});

	public static void main(String[] args) throws IOException {
		allObservationsStore = new RandomAccessFile("/opt/local/phenocube/allObservationsStore.javabin", "rw");
		initialLoad();
		saveStore();
	}

	private static void saveStore() throws FileNotFoundException, IOException {
		store.asMap().forEach((String key, PhenoCube value)->{
			metadataMap.put(key, new ColumnMeta().setName(key).setWidthInBytes(value.getColumnWidth()).setCategorical(value.isStringType()));
		});
		store.invalidateAll();
		ObjectOutputStream metaOut = new ObjectOutputStream(new FileOutputStream(new File("/opt/local/phenocube/columnMeta.javabin")));
		metaOut.writeObject(metadataMap);
		metaOut.flush();
		metaOut.close();
		allObservationsStore.close();
	}

	private static void initialLoad() throws IOException {
		Reader in = new FileReader("/opt/local/phenocube/allConcepts.csv");
		Iterable<CSVRecord> records = CSVFormat.DEFAULT.withSkipHeaderRecord().withFirstRecordAsHeader().parse(in);

		final PhenoCube[] currentConcept = new PhenoCube[1];
		for (CSVRecord record : records) {
			processRecord(currentConcept, record);
		}
	}

	private static void processRecord(final PhenoCube[] currentConcept, CSVRecord record) {
		if(record.size()<4) {
			log.info("Record number " + record.getRecordNumber() 
			+ " had less records than we expected so we are skipping it.");
			return;
		}

		try {
			String conceptPath = record.get(CONCEPT_PATH).endsWith("\\" +record.get(TEXT_VALUE).trim()+"\\") ? record.get(CONCEPT_PATH).replaceAll("\\\\[\\w\\.-]*\\\\$", "\\\\") : record.get(CONCEPT_PATH);
			String numericValue = record.get(NUMERIC_VALUE);
			if(numericValue==null || numericValue.isEmpty()) {
				try {
					numericValue = Float.parseFloat(record.get(TEXT_VALUE).trim()) + "";
				}catch(NumberFormatException e) {
					log.info("Record number " + record.getRecordNumber() 
					+ " had an alpha value where we expected a number in the alpha column... "
					+ "which sounds weirder than it really is.");

				}
			}
			boolean isAlpha = (numericValue == null || numericValue.isEmpty());
			if(currentConcept[0] == null || !currentConcept[0].name.equals(conceptPath)) {
				try {
					currentConcept[0] = store.get(conceptPath);
				} catch(InvalidCacheLoadException e) {
					currentConcept[0] = new PhenoCube(conceptPath, isAlpha ? String.class : Float.class);
					store.put(conceptPath, currentConcept[0]);
				}
			}
			String value = isAlpha ? record.get(TEXT_VALUE) : numericValue;

			if(value != null && !value.trim().isEmpty() && ((isAlpha && currentConcept[0].vType == String.class)||(!isAlpha && currentConcept[0].vType == Float.class))) {
				value = value.trim();
				currentConcept[0].setColumnWidth(isAlpha ? Math.max(currentConcept[0].getColumnWidth(), value.getBytes().length) : Float.BYTES);
				currentConcept[0].add(Integer.parseInt(record.get(PATIENT_NUM).trim()), isAlpha ? value : Float.parseFloat(value));
			}
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
}