/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mini;

import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.server.master.Master;
import org.apache.accumulo.server.tabletserver.TabletServer;
import org.apache.accumulo.server.util.Initialize;
import org.apache.accumulo.server.util.PortUtils;
import org.apache.accumulo.server.util.time.SimpleTimer;
import org.apache.accumulo.start.Main;
import org.apache.zookeeper.server.ZooKeeperServerMain;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * A utility class that will create Zookeeper and Accumulo processes that write all of their data to a single local directory. This class makes it easy to test
 * code against a real Accumulo instance. Its much more accurate for testing than MockAccumulo, but much slower than MockAccumulo.
 *
 * @since 1.5.0
 */
public class MiniAccumuloCluster {

    private static final String INSTANCE_SECRET = "DONTTELL";

    private File confDir;
    private File zooCfgFile;
    private File logDir;

    private Process zooKeeperProcess;
    private Process masterProcess;

    private String instanceName;

    private List<LogWriter> logWriters = new ArrayList<MiniAccumuloCluster.LogWriter>();

    private MiniAccumuloConfig config;
    private Process[] tabletServerProcesses;


    private static class LogWriter extends Thread {
        private BufferedReader in;
        private BufferedWriter out;

        /**
         * @throws java.io.IOException
         */
        public LogWriter(InputStream stream, File logFile) throws IOException {
            this.setDaemon(true);
            this.in = new BufferedReader(new InputStreamReader(stream));
            out = new BufferedWriter(new FileWriter(logFile));

            SimpleTimer.getInstance().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }, 1000, 1000);
        }

        public synchronized void flush() throws IOException {
            if (out != null)
                out.flush();
        }

        @Override
        public void run() {
            String line;

            try {
                while ((line = in.readLine()) != null) {
                    out.append(line);
                    out.append("\n");
                }

                synchronized (this) {
                    out.close();
                    out = null;
                    in.close();
                }

            } catch (IOException ignored) {}
        }
    }



    private Process exec(Class<? extends Object> clazz, String... args) throws IOException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        classpath = confDir.getAbsolutePath() + File.pathSeparator + classpath;

        String className = clazz.getCanonicalName();

        ArrayList<String> argList = new ArrayList<String>();

        argList.addAll(Arrays.asList(javaBin, "-cp", classpath, "-Xmx128m", "-XX:+UseConcMarkSweepGC", "-XX:CMSInitiatingOccupancyFraction=75",
                Main.class.getName(), className));

        argList.addAll(Arrays.asList(args));

        ProcessBuilder builder = new ProcessBuilder(argList);

        builder.environment().put("ACCUMULO_HOME", config.getDir().getAbsolutePath());
        builder.environment().put("ACCUMULO_LOG_DIR", logDir.getAbsolutePath());

        // if we're running under accumulo.start, we forward these env vars
        String env = System.getenv("HADOOP_PREFIX");
        if (env != null)
            builder.environment().put("HADOOP_PREFIX", env);
        env = System.getenv("HADOOP_HOME");
        if (env != null)
            builder.environment().put("HADOOP_HOME", env);
        env = System.getenv("ZOOKEEPER_HOME");
        if (env != null)
            builder.environment().put("ZOOKEEPER_HOME", env);

        Process process = builder.start();

        LogWriter lw;
        lw = new LogWriter(process.getErrorStream(), new File(logDir, clazz.getSimpleName() + "_" + process.hashCode() + ".err"));
        logWriters.add(lw);
        lw.start();
        lw = new LogWriter(process.getInputStream(), new File(logDir, clazz.getSimpleName() + "_" + process.hashCode() + ".out"));
        logWriters.add(lw);
        lw.start();

        return process;
    }

    private void appendProp(FileWriter fileWriter, Property key, String value, Map<String,String> siteConfig) throws IOException {
        appendProp(fileWriter, key.getKey(), value, siteConfig);
    }

    private void appendProp(FileWriter fileWriter, String key, String value, Map<String,String> siteConfig) throws IOException {
        if (!siteConfig.containsKey(key))
            fileWriter.append("<property><name>" + key + "</name><value>" + value + "</value></property>\n");
    }

    /**
     *
     * @param dir
     *          An empty or nonexistant temp directoy that Accumulo and Zookeeper can store data in. Creating the directory is left to the user. Java 7, Guava,
     *          and Junit provide methods for creating temporary directories.
     * @param rootPassword
     *          Initial root password for instance.
     * @throws java.io.IOException
     */
    public MiniAccumuloCluster(File dir, String instanceName, String rootPassword) throws IOException {
        this(new MiniAccumuloConfig(dir, instanceName, rootPassword));
    }

    /**
     * @param config
     *          initial configuration
     * @throws java.io.IOException
     */

    public MiniAccumuloCluster(MiniAccumuloConfig config) throws IOException {

        if (config.getDir().exists() && !config.getDir().isDirectory())
            throw new IllegalArgumentException("Must pass in directory, " + config.getDir() + " is a file");

        if (config.getDir().exists() && config.getDir().list().length != 0)
            throw new IllegalArgumentException("Directory " + config.getDir() + " is not empty");

        this.config = config;

        File libDir = new File(config.getDir(), "lib");
        confDir = new File(config.getDir(), "conf");
        File accumuloDir = new File(config.getDir(), "accumulo");
        File zooKeeperDir = new File(config.getDir(), "zookeeper");
        logDir = new File(config.getDir(), "logs");
        File walogDir = new File(config.getDir(), "walogs");

        confDir.mkdirs();
        accumuloDir.mkdirs();
        zooKeeperDir.mkdirs();
        logDir.mkdirs();
        walogDir.mkdirs();
        libDir.mkdirs();

        File accumuloLibDir = new File(System.getenv("ACCUMULO_HOME") + "/lib/");

        copyFileOrDirectory(accumuloLibDir,libDir);

        instanceName = config.getInstanceName();

        File siteFile = new File(confDir, "accumulo-site.xml");

        FileWriter fileWriter = new FileWriter(siteFile);
        fileWriter.append("<configuration>\n");

        HashMap<String,String> siteConfig = new HashMap<String,String>(config.getSiteConfig());

        appendProp(fileWriter, Property.INSTANCE_DFS_URI, "file:///", siteConfig);
        appendProp(fileWriter, Property.INSTANCE_DFS_DIR, accumuloDir.getAbsolutePath(), siteConfig);
        appendProp(fileWriter, Property.INSTANCE_ZK_HOST, "localhost:" + config.getZooKeeperPort(), siteConfig);
        appendProp(fileWriter, Property.INSTANCE_SECRET, INSTANCE_SECRET, siteConfig);
        appendProp(fileWriter, Property.MASTER_CLIENTPORT, "" + PortUtils.getRandomFreePort(), siteConfig);
        appendProp(fileWriter, Property.TSERV_CLIENTPORT, "" + PortUtils.getRandomFreePort(), siteConfig);
        appendProp(fileWriter, Property.TSERV_PORTSEARCH, "true", siteConfig);
        appendProp(fileWriter, Property.LOGGER_DIR, walogDir.getAbsolutePath(), siteConfig);
        appendProp(fileWriter, Property.TSERV_DATACACHE_SIZE, "10M", siteConfig);
        appendProp(fileWriter, Property.TSERV_INDEXCACHE_SIZE, "10M", siteConfig);
        appendProp(fileWriter, Property.TSERV_MAXMEM, "50M", siteConfig);
        appendProp(fileWriter, Property.TSERV_WALOG_MAX_SIZE, "100M", siteConfig);
        appendProp(fileWriter, Property.TSERV_NATIVEMAP_ENABLED, "false", siteConfig);
        appendProp(fileWriter, Property.TRACE_TOKEN_PROPERTY_PREFIX + ".password", config.getRootPassword(), siteConfig);
        appendProp(fileWriter, Property.TRACE_PORT, "" + PortUtils.getRandomFreePort(), siteConfig);
        // since there is a small amount of memory, check more frequently for majc... setting may not be needed in 1.5
        appendProp(fileWriter, Property.TSERV_MAJC_DELAY, "3", siteConfig);
        String cp = System.getenv("ACCUMULO_HOME") + "/lib/.*.jar,"
                + System.getenv("ACCUMULO_HOME") + "/lib/ext/.*.jar,"
                + System.getenv("ZOOKEEPER_HOME") + "/zookeeper[^.].*.jar,"
                + System.getenv("HADOOP_HOME") + "/[^.].*.jar,"
                + System.getenv("HADOOP_HOME") + "/lib/[^.].*.jar,"
                + System.getenv("HADOOP_PREFIX") + "/share/hadoop/common/.*.jar,"
                + System.getenv("HADOOP_PREFIX") + "/share/hadoop/common/lib/.*.jar,"
                + System.getenv("HADOOP_PREFIX") + "/share/hadoop/hdfs/.*.jar,"
                + System.getenv("HADOOP_PREFIX") + "/share/hadoop/mapreduce/.*.jar";
        appendProp(fileWriter, Property.GENERAL_CLASSPATHS, cp, siteConfig);
        appendProp(fileWriter, Property.GENERAL_DYNAMIC_CLASSPATHS, libDir.getAbsolutePath(), siteConfig);

        for (Entry<String,String> entry : siteConfig.entrySet())
            fileWriter.append("<property><name>" + entry.getKey() + "</name><value>" + entry.getValue() + "</value></property>\n");
        fileWriter.append("</configuration>\n");
        fileWriter.close();

        zooCfgFile = new File(confDir, "zoo.cfg");
        fileWriter = new FileWriter(zooCfgFile);

        // zookeeper uses Properties to read its config, so use that to write in order to properly escape things like Windows paths
        Properties zooCfg = new Properties();
        zooCfg.setProperty("tickTime", "1000");
        zooCfg.setProperty("initLimit", "10");
        zooCfg.setProperty("syncLimit", "5");
        zooCfg.setProperty("clientPort", config.getZooKeeperPort() + "");
        zooCfg.setProperty("maxClientCnxns", "100");
        zooCfg.setProperty("dataDir", zooKeeperDir.getAbsolutePath());
        zooCfg.store(fileWriter, null);

        fileWriter.close();

    }

    public static void copyFileOrDirectory(File src, File dest)
            throws IOException{

        if(src.isDirectory()){

            //if directory not exists, create it
            if(!dest.exists()){
                dest.mkdir();
         }

            for (String file :  src.list()) {
                //construct the src and dest file structure
                File srcFile = new File(src, file);
                File destFile = new File(dest, file);
                //recursive copy
                copyFileOrDirectory(srcFile, destFile);
            }

        }else{
            //if file, then copy it
            //Use bytes stream to support all file types
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dest);

            byte[] buffer = new byte[1024];

            int length;
            //copy the file content in bytes
            while ((length = in.read(buffer)) > 0){
                out.write(buffer, 0, length);
            }
            in.close();
            out.close();
        }
    }


    /**
     * Starts Accumulo and Zookeeper processes. Can only be called once.
     *
     * @throws java.io.IOException
     * @throws InterruptedException
     * @throws IllegalStateException
     *           if already started
     */

    public void start() throws IOException, InterruptedException {
        if (zooKeeperProcess != null)
            throw new IllegalStateException("Already started");

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    MiniAccumuloCluster.this.stop();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        zooKeeperProcess = exec(Main.class, ZooKeeperServerMain.class.getName(), zooCfgFile.getAbsolutePath());

        // sleep a little bit to let zookeeper come up before calling init, seems to work better
        UtilWaitThread.sleep(250);

        Process initProcess = exec(Initialize.class, "--instance-name", instanceName, "--password", config.getRootPassword());
        int ret = initProcess.waitFor();
        if (ret != 0) {
            throw new RuntimeException("Initialize process returned " + ret);
        }

        tabletServerProcesses = new Process[config.getNumTservers()];
        for (int i = 0; i < config.getNumTservers(); i++) {
            tabletServerProcesses[i] = exec(TabletServer.class);
        }

        masterProcess = exec(Master.class);
    }

    /**
     * @return Accumulo instance name
     */

    public String getInstanceName() {
        return instanceName;


    }

    public File getLogDir() {
        return logDir;
    }


    public MiniAccumuloConfig getConfig() {
        return config;
    }

    /**
     * Stops Accumulo and Zookeeper processes. If stop is not called, there is a shutdown hook that is setup to kill the processes. Howerver its probably best to
     * call stop in a finally block as soon as possible.
     *
     * @throws java.io.IOException
     * @throws InterruptedException
     */

    public void stop() throws IOException, InterruptedException {
        if (zooKeeperProcess != null)
            zooKeeperProcess.destroy();
        if (masterProcess != null)
            masterProcess.destroy();
        if (tabletServerProcesses != null) {
            for (Process tserver : tabletServerProcesses) {
                tserver.destroy();
            }
        }

        for (LogWriter lw : logWriters)
            lw.flush();
    }
}
