package edu.umd.nkher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;


public class BooleanRetrievalHBase extends Configured implements Tool {
	
	Logger LOG = Logger.getLogger(BooleanRetrievalHBase.class);
	
	private FSDataInputStream collection;
	private HConnection hbaseconnection;
	private HTableInterface table;
	private Get get;
	private Result result;
	private Stack<Set<Integer>> stack;
	
	
	private BooleanRetrievalHBase() {}
	
	private void initialize(Configuration hbaseConfig, String tableName, String collectionPath, FileSystem fs) throws IOException {
		collection = fs.open(new Path(collectionPath));
		hbaseconnection = HConnectionManager.createConnection(hbaseConfig);
		table = hbaseconnection.getTable(tableName);
		stack = new Stack<Set<Integer>>();
	}
	
	private void runQuery(String q) throws IOException {
		String[] terms = q.split("\\s+");
		for (String t : terms) {
			if (t.equals("AND")) {
				performAND();
			} else if (t.equals("OR")) {
				performOR();
			} else {
				pushTerm(t);
			}
		}
		Set<Integer> set = stack.pop();
		for (Integer i : set) {
			String line = fetchLine(i);
            if (line.length() >= 100) {
                line = line.substring(0, 100);
            }
			System.out.println(i + "\t" + line);
		}
	}
	private void pushTerm(String term) throws IOException {
		stack.push(fetchDocumentSet(term));
	}
	private void performAND() {
		Set<Integer> s1 = stack.pop();
		Set<Integer> s2 = stack.pop();
		Set<Integer> sn = new TreeSet<Integer>();
		for (int n : s1) {
			if (s2.contains(n)) {
				sn.add(n);
			}
		}
		stack.push(sn);
	}
	private void performOR() {
		Set<Integer> s1 = stack.pop();
		Set<Integer> s2 = stack.pop();
		Set<Integer> sn = new TreeSet<Integer>();
		for (int n : s1) {
			sn.add(n);
		}
		for (int n : s2) {
			sn.add(n);
		}
		stack.push(sn);
	}
	
	@SuppressWarnings("deprecation")
	private Set<Integer> fetchDocumentSet(String term) throws IOException {
		Set<Integer> set = new TreeSet<Integer>();
		Result result = fetchPostings(term);
		Cell[] cells = result.rawCells();
		for (Cell cell : cells) {
			// System.out.println("Doc number : " + Bytes.toInt(cell.getQualifier()) + " TF : " + Bytes.toInt(cell.getValue()));
			set.add(Bytes.toInt(cell.getQualifier())); // inserting the document number
		}
		return set;
	}
	
	private Result fetchPostings(String term) throws IOException {
		get = new Get(Bytes.toBytes(term));
		result = table.get(get);
		return result;
	}
	
	private String fetchLine(long offset) throws IOException {
		collection.seek(offset);
		BufferedReader reader = new BufferedReader(new InputStreamReader(collection));
		return reader.readLine();
	}

	
	private static final String INDEX = "index";
	private static final String COLLECTION = "collection";
	/**
	 * Runs this tool.
	 */
	@SuppressWarnings({ "static-access" })
	public int run(String[] args) throws Exception {
		Options options = new Options();
		options.addOption(OptionBuilder.withArgName("index").hasArg()
				.withDescription("HBase table name").create(INDEX));
		options.addOption(OptionBuilder.withArgName("collection").hasArg()
				.withDescription("HBase table name").create(COLLECTION));
//		options.addOption(OptionBuilder.withArgName("word").hasArg()
//				.withDescription("word to look up").create(WORD));
		CommandLine cmdline;
		CommandLineParser parser = new GnuParser();
		try {
			cmdline = parser.parse(options, args);
		} catch (ParseException exp) {
			System.err.println("Error parsing command line: " + exp.getMessage());
			return -1;
		}
		if (!cmdline.hasOption(INDEX) || !cmdline.hasOption(COLLECTION)) {
			System.out.println("args: " + Arrays.toString(args));
			HelpFormatter formatter = new HelpFormatter();
			formatter.setWidth(120);
			formatter.printHelp(this.getClass().getName(), options);
			ToolRunner.printGenericCommandUsage(System.out);
			return -1;
		}
		
		String tableName = cmdline.getOptionValue(INDEX);
		String collectionPath = cmdline.getOptionValue(COLLECTION);

		if (collectionPath.endsWith(".gz")) {
			System.out.println("gzipped collection is not seekable: use compressed version!");
			System.exit(-1);
		}
		
		FileSystem fs = FileSystem.get(new Configuration());
			
		Configuration conf = getConf();
		conf.addResource(new Path("/etc/hbase/conf/hbase-site.xml"));
		Configuration hbaseConfig = HBaseConfiguration.create(conf);

		initialize(hbaseConfig, tableName, collectionPath, fs);
				
		String[] queries = { "outrageous fortune AND", "white rose AND", "means deceit AND",
				"white red OR rose AND pluck AND", "unhappy outrageous OR good your AND OR fortune AND" };
		for (String q : queries) {
			System.out.println("Query: " + q);
			runQuery(q);
			System.out.println("");
		}
		return 1;
	}
	/**
	 * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
	 */
	public static void main(String[] args) throws Exception {
		ToolRunner.run(new BooleanRetrievalHBase(), args);
	}
}