import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

public class MasterMain {
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		long startTime = System.currentTimeMillis();
		
		int n_workers = 3;
		
		String machines_path = "/Users/andre.farias/Desktop/MSBigData_GitHub/INF727_DistSys/Projects/Machines_TP.txt";
		
		String input_file_repo = "/Users/andre.farias/Desktop/MSBigData_GitHub/INF727_DistSys/Projects/Files/Input_file/";
		
		String input_file_path = input_file_repo + "input.txt"; //args[0];
		
		createSplits(input_file_path, n_workers);
		
		ArrayList<String> machines = readMachines(machines_path);

		ArrayList<String> workers = workingMachines(machines, n_workers);
		
		makeDir(workers);
		
		// create list of files
		ArrayList<String> files = new ArrayList<String>();
		for(int i = 0; i < n_workers; i++) {
			files.add("S"+i+".txt");
		}
		
		HashMap<String,String> workers_to_Sfiles =  machineFilesDict(workers,files);
		
		copySFiles(workers_to_Sfiles);
		
		// Dictionary of keywords -> list of UM*
		HashMap<String,ArrayList<String>> key_words_to_um = new HashMap<String,ArrayList<String>>();
		// Dictionary of UM* -> worker
		HashMap<String,String> um_to_worker = new HashMap<String,String>();
		
		launchMap(workers_to_Sfiles, key_words_to_um, um_to_worker);
		
		HashMap<String,String> sm_file_to_key = launchShuffle(um_to_worker, key_words_to_um, workers);
		
		HashMap<String,String> rm_to_key = launchReduce(workers, sm_file_to_key);
		
		System.out.println("\nResults from word count:");
		
		HashMap<String,Integer> results = resultsFromReduce(workers, rm_to_key);
		
		createOuputFile(results);
		
		long endTime = System.currentTimeMillis();	
		long totalTime = endTime - startTime;
		System.out.format("\nThe time of execution is %dms",totalTime);

	}

	public static ArrayList<String> readMachines(String path) throws IOException{
		FileReader in = new FileReader(path);
		BufferedReader br = new BufferedReader(in);
		ArrayList<String> machines = new ArrayList<String>();
		String line = null;
		while ((line = br.readLine()) != null) {
			machines.add(line);
		}
		in.close();
		br.close();
		return machines;
	}
	
	public static ArrayList<String> workingMachines(ArrayList<String> machines, int n_workers) throws IOException, InterruptedException {
		
		ArrayList<String> workers = new ArrayList<String>();
		
		// Launch parallelized processes  
		
		// HashMap to keep track of <machine, process>
		HashMap<String,Process> processMap = new HashMap<String,Process>(); 
		
		for(String machine : machines) {
			ProcessBuilder pb = new ProcessBuilder("ssh", "amacedo@"+machine,"hostname");
			Process p = pb.start();
			processMap.put(machine,p);
		}
		// Iterate over HashMap using wait for to check which machines can be connected
		for(Entry<String, Process> e: processMap.entrySet()) {
			Process p = e.getValue();
			String machine = e.getKey();
			
			if(p.waitFor(5,TimeUnit.SECONDS)) {
				String out_is = outputTest(p.getInputStream());
				if(out_is.equals(machine)){
					workers.add(machine);
				}
			}
		}
		if(workers.size() >= n_workers) {
			workers = new ArrayList<String> (workers.subList(0,n_workers));
			System.out.println("Workers: "+workers+"\n");
		}
		else {
			System.out.println("There are not enough connected machines");
		}
		return workers;
	}
	
	public static String outputTest(InputStream inputStream) throws IOException {
		BufferedReader br = null;
		ArrayList<String> outputs = new ArrayList<String>();
		String line = null;
		try {
			br = new BufferedReader(new InputStreamReader(inputStream));
			while ((line = br.readLine()) != null) {
				outputs.add(line);
			}
		} finally {
			br.close();
		}
		if(!outputs.isEmpty()){
			return outputs.get(0);
		}else {
			return "";
		}	
	}
	
	public static void makeDir(ArrayList<String> machines) throws IOException, InterruptedException {
		
		// List to keep track of process
		ArrayList<Process> prList = new ArrayList<Process>(); 
		
		for(String machine: machines) {		
			ProcessBuilder pb = new ProcessBuilder("ssh","amacedo@"+machine,"mkdir","-p","/tmp/amacedo/splits");
			Process p = pb.start();
			prList.add(p);
		}
		for(Process p: prList) {
			p.waitFor(10,TimeUnit.SECONDS);
		}
		System.out.println("mkdir process finalized");
	}
	
	public static HashMap<String,String> machineFilesDict(ArrayList<String> machines, ArrayList<String> files){
		
		HashMap<String,String> dict = new HashMap<String,String>();
		
		int i = 0;	// tracks the machine index
		for(String file: files) {
			String machine = machines.get(i);
			dict.put(machine, file);
			i = i+1;
		}
		return dict;
	}

	public static void copySFiles(HashMap<String,String> dict) throws IOException, InterruptedException {
		
		String files_repo = "/Users/andre.farias/Desktop/MSBigData_GitHub/INF727_DistSys/Projects/Files/";
		// List to keep track of process
		ArrayList<Process> prList = new ArrayList<Process>();
		for(Entry<String,String> e: dict.entrySet()) {
			String machine = e.getKey();
			String file = e.getValue();
			ProcessBuilder pb = new ProcessBuilder("scp", files_repo+file,"amacedo@"+machine+":/tmp/amacedo/splits");
			Process p = pb.start();
			prList.add(p);		
		}
		
		for(Process p: prList) {
			p.waitFor(10,TimeUnit.SECONDS);
		}
		System.out.println("copyFiles process finalized");
	}	
	
	public static void launchMap(HashMap<String,String> worker_to_Sfiles,
								HashMap<String,ArrayList<String>> key_words_to_um,
								HashMap<String,String> um_to_worker) throws InterruptedException, IOException {
		
		// HashMap to keep track of processes and machines
		HashMap<String,Process> um_file_to_process = new HashMap<String,Process>();
		
		String files_dir = "/tmp/amacedo/splits/";
		
		for(Entry<String,String> e: worker_to_Sfiles.entrySet()) {
			String machine = e.getKey();
			String infile = e.getValue();
			ProcessBuilder pb = new ProcessBuilder("ssh","amacedo@"+machine,"java","-jar",
						                              "/tmp/amacedo/SLAVE.jar","0",files_dir+infile);
			Process p = pb.start();
			String um_file = getUmName(infile);
			um_file_to_process.put(um_file,p);
		}
		
		for(Entry<String, Process> e: um_file_to_process.entrySet()) {
			Process p = e.getValue();
			String um_file = e.getKey();
			p.waitFor(10,TimeUnit.SECONDS);
			InputStream is = p.getInputStream();
			ArrayList<String> key_words_list = outputUm(is);
			for(String word: key_words_list) {
				insertMap(key_words_to_um,word,um_file);
			}
			
		}
		System.out.println("\nKey_words to UM file dictionary:");
		System.out.println(key_words_to_um);
		
		for(Entry<String,String> e: worker_to_Sfiles.entrySet()) {
			String machine = e.getKey();
			String infile = e.getValue();
			um_to_worker.put(getUmName(infile), machine);
		}
		System.out.println("\nUM file to machine dictionary:");
		System.out.println(um_to_worker);
	}
	
	private static ArrayList<String> outputUm(InputStream inputStream) throws IOException {
		ArrayList<String> output = new ArrayList<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(inputStream));
			String line = null;
			while ((line = br.readLine()) != null) {
				if(!line.equals("")) {
					output.add(line);
				}
			}
		} finally {
			br.close();
		}
		return output;
	}
	
	public static void insertMap(HashMap<String,ArrayList<String>> dict, String word_key, String um_file) {
		if(dict.containsKey(word_key)) {
			ArrayList<String> umList = dict.get(word_key);
			umList.add(um_file);
			dict.replace(word_key, umList);
		}else {
			ArrayList<String> umList = new ArrayList<String>();
			umList.add(um_file);
			dict.put(word_key, umList);
		}
	}
	
	public static String getUmName(String input_file) {
		String txt_number = input_file.substring(1, input_file.length()-4);
		return "UM" + txt_number + ".txt";
	}
	
	public static HashMap<String,String> launchShuffle(HashMap<String,String> um_to_worker, 
									HashMap<String,ArrayList<String>> key_word_to_um, 
									ArrayList<String> workers) throws IOException, InterruptedException {
		
		HashMap<String,String> sm_file_to_key = new HashMap<String,String>(); 
		
		int i = 0; // integer used to iterate over machines
		int n_workers = workers.size();
		
		// Copying files from origin machines to target machines
		ArrayList<Process> prList = new ArrayList<Process>();
		for(Entry<String,ArrayList<String>> e: key_word_to_um.entrySet()) {
			ArrayList<String> um_list = e.getValue();
			String target_worker = workers.get(i);
			for(String um_file: um_list) {
				String origin_worker = um_to_worker.get(um_file);
				if(!origin_worker.equals(target_worker)) {
					ProcessBuilder pb = new ProcessBuilder("scp", "amacedo@" + origin_worker + ":/tmp/amacedo/maps/" 
							+ um_file, "amacedo@" + target_worker + ":/tmp/amacedo/maps").inheritIO();
					Process p = pb.start();
					prList.add(p);	
				}
			}
			i = (i+1) % n_workers ; // iterating over workers list
		}
		
		// Wait for every copy process to end in order to launch shuffle mode in SLAVE
		for(Process p: prList) {
			p.waitFor(10,TimeUnit.SECONDS);
		}
		
		// Shuffle mode in each SLAVE
		i = 0; // reinitiate iteration over workers
		int smNum = 0; // counter of SM files
		ArrayList<Process> prList2 = new ArrayList<Process>();
		for(Entry<String,ArrayList<String>> e: key_word_to_um.entrySet()) {
			String key = e.getKey();
			ArrayList<String> um_list = e.getValue();
			String um_list_str = "";
			String worker = workers.get(i);
			String sm_file = "/tmp/amacedo/maps/SM" + Integer.toString(smNum) + ".txt";
			sm_file_to_key.put(sm_file, key);
			for(String um_file: um_list) {
				um_list_str += "/tmp/amacedo/maps/" + um_file + " ";
			}
			ProcessBuilder pb = new ProcessBuilder("ssh", "amacedo@" + worker, "java"+" "+ "-jar"+" "+ 
					"/tmp/amacedo/SLAVE.jar"+" "+"1"+" "+"\""+key +"\""+" "+sm_file+" "+um_list_str);
			Process p = pb.start();
			prList2.add(p);
			i = (i+1) % n_workers ; // iterating over workers list
			smNum++ ;// iterate over number of SM file
		}
		System.out.println("\n");
		// Wait for every copy process to end in order to launch shuffle mode in SLAVE
		for(Process p: prList2) {
			p.waitFor(10,TimeUnit.SECONDS);
		}
		System.out.println("\nSM file to key-word dictionary:");
		System.out.println(sm_file_to_key);
		return sm_file_to_key;
					
	}
	
	public static HashMap<String,String> launchReduce(ArrayList<String> workers, HashMap<String,String> sm_file_to_key) throws IOException, InterruptedException {
		
		HashMap<String,String> rm_to_key = new HashMap<String,String>();
		
		int i = 0; // reinitiate iteration over workers
		int n_workers = workers.size();
		ArrayList<Process> prList = new ArrayList<Process>();
		for(int smNum = 0; smNum < sm_file_to_key.keySet().size(); smNum++) {
			String sm_file = "/tmp/amacedo/maps/SM" + Integer.toString(smNum) + ".txt";
			String rm_file = "/tmp/amacedo/reduces/RM" + Integer.toString(smNum) + ".txt";
			String worker = workers.get(i);
			String key = sm_file_to_key.get(sm_file);
			rm_to_key.put(rm_file, key);
			ProcessBuilder pb = new ProcessBuilder("ssh", "amacedo@" + worker, "java", "-jar", 
					"/tmp/amacedo/SLAVE.jar","2",key,sm_file,rm_file);
			Process p = pb.start();
			prList.add(p);
			i = (i+1) % n_workers ; // iterating over workers list
		}
		// Wait for every copy process to end in order to launch shuffle mode in SLAVE
		for(Process p: prList) {
			p.waitFor(10,TimeUnit.SECONDS);
		}
		return rm_to_key;
		
	}
	
	public static HashMap<String,Integer> resultsFromReduce(ArrayList<String> workers, HashMap<String,String> rm_to_key) throws IOException, InterruptedException {
		
		HashMap<String,Integer> results = new HashMap<String,Integer>();
		ArrayList<Process> prList = new ArrayList<Process>();
		int i = 0; // reinitiate iteration over workers
		int n_workers = workers.size();
		for(int smNum = 0; smNum < rm_to_key.keySet().size(); smNum++) {
			String rm_file = "/tmp/amacedo/reduces/RM" + Integer.toString(smNum) + ".txt";
			String worker = workers.get(i);
			ProcessBuilder pb = new ProcessBuilder("ssh", "amacedo@" + worker, "cat",rm_file);
			Process p = pb.start();
			prList.add(p);
			i = (i+1) % n_workers ; // iterating over workers list
			
		}
		
		for(Process p: prList) {
			p.waitFor(10,TimeUnit.SECONDS);
			InputStream is = p.getInputStream();
			String result = outputResult(is);
			if(result != null) {
				String word = result.split(" ")[0];
				Integer count = Integer.parseInt(result.split(" ")[1]);
				results.put(word, count);
				System.out.println(word + ": " + count.toString());
			}
		}
		return results;
		
	}
	
	public static String outputResult(InputStream inputStream) throws IOException {
		ArrayList<String> output = new ArrayList<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(inputStream));
			String line = null;
			while ((line = br.readLine()) != null) {
				output.add(line);
			}
		} finally {
			br.close();
		}
		if(!output.isEmpty()) {
			return output.get(0);
		}
		else {
			return null;
		}
	}
	
	public static void createOuputFile(HashMap<String, Integer> results) throws IOException, InterruptedException {
		
		String outputs_dir = "/Users/andre.farias/Desktop/MSBigData_GitHub/INF727_DistSys/Projects/Output";
		
		Process p = new ProcessBuilder("mkdir", outputs_dir).start();
		p.waitFor(10,TimeUnit.SECONDS);
		
		FileWriter writer;
		writer = new FileWriter(outputs_dir + "outputs.txt");
			for(Entry<String, Integer> e: results.entrySet()) {
				String word = e.getKey();
				String count = e.getValue().toString();
				writer.write(word + " " + count + "\n");
			}

		writer.close();
	}
	
	public static void createSplits(String input_file, int n_workers) throws IOException {
		
		ArrayList<FileWriter> writers = new ArrayList<FileWriter>();
		for(Integer i=0; i<n_workers; i++) {
			String split_path = "/Users/andre.farias/Desktop/MSBigData_GitHub/INF727_DistSys/Projects/Files/S" + i.toString() + ".txt";
			FileWriter writer = new FileWriter(split_path);
			writers.add(writer);
		}
		
		FileReader in = new FileReader(input_file);
		BufferedReader br = new BufferedReader(in);
		int j = 0 ; // counter to add to write each line in a different split file
		String line = null;
		while((line = br.readLine()) != null) {
			writers.get(j).write(line + " ");
			j = (j+1) % n_workers;
		}
		in.close();
		br.close();
		
		for(FileWriter writer: writers) {
			writer.close();
		}
		
	}
	
}
