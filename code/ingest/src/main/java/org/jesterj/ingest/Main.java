/*
 * Copyright 2014 Needham Software LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jesterj.ingest;

import com.google.common.io.Resources;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.docopt.Docopt;
import org.jesterj.ingest.forkjoin.JesterJForkJoinThreadFactory;
import org.jesterj.ingest.model.Plan;
import org.jesterj.ingest.persistence.Cassandra;
import org.jesterj.ingest.utils.JesterjPolicy;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;

/*
 * Created with IntelliJ IDEA.
 * User: gus
 * Date: 7/5/14
 */

/**
 * Start a running instance. Each instance should have an id and a password (freely chosen
 * by the user starting the process. The ID will be used to display the node in the control
 * console and the password is meant to provide temporary security until the node is
 * configured properly.
 */
public class Main {

  // WARNING: do not add a logger init to this class! See below for classloading highjinks that
  // force us to wait to initialize logging
  private static Logger log;

  private static final Object HAPPENS_BEFORE = new Object();

  public static String JJ_DIR;
  private static Thread DUMMY_HOOK = new Thread();

  static {
    // set up a config dir in user's home dir
    String userDir = System.getProperty("user.home");
    File jjDir = new File(userDir + "/.jj");
    if (!jjDir.exists() && !jjDir.mkdir()) {
      throw new RuntimeException("could not create " + jjDir);
    } else {
      try {
        JJ_DIR = jjDir.getCanonicalPath();
      } catch (IOException e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
  }

  public static void main(String[] args) {
    synchronized (HAPPENS_BEFORE) {
      try {
        System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", JesterJForkJoinThreadFactory.class.getName());
        System.setProperty("cassandra.insecure.udf", "true");
        // set up log output dir
        String logDir = System.getProperty("jj.log.dir");
        if (logDir == null) {
          System.setProperty("jj.log.dir", JJ_DIR + "/logs");
        }
        logDir = System.getProperty("jj.log.dir");

        // Check that we can write to the log dir
        File logDirFile = new File(logDir);

        if (!logDirFile.mkdirs() && !(logDirFile.canWrite())) {
          System.out.println("Cannot write to " + logDir + " \n" +
              "Please fix the filesystem permissions or provide a writable location with -Djj.log.dir property on the command line.");
          System.exit(99);
        }

        System.out.println("Logs will be written to: " + logDir);

        initClassloader();

        String logConfig = logDir + "/log4j2.xml";
        System.setProperty("log4j.configurationFile", logConfig);
        File configFile = new File(logConfig);
        if (!configFile.exists()) {
          InputStream log4jxml = Main.class.getResourceAsStream("/log4j2.xml");
          Files.copy(log4jxml, configFile.toPath());
        }

        Thread contextClassLoaderFix = new Thread(() -> {
          // ensure that the main method completes before this thread runs.
          synchronized (HAPPENS_BEFORE) {
            try {
              initRMI();

              // Next check our args and die if they are FUBAR
              Map<String, Object> parsedArgs = usage(args);
              String outfile = (String) parsedArgs.get("-z");

              String javaConfig = (String) parsedArgs.get("<plan.jar>");
              System.out.println("Looking for configuration class in " + javaConfig);
              if (outfile != null) {
                // in this case we aren't starting a node, and we don't care if logging doesn't make it to
                // cassandra (in fact better if it doesn't) so go ahead and call what we like INSIDE this if
                // block only.
                Plan p = loadJavaConfig(javaConfig);
                System.out.println("Generating visualization for " + p.getName() + " into " + outfile);
                BufferedImage img = p.visualize();
                ImageIO.write(img, "PNG", new File(outfile));
                System.exit(0);
              }
              startCassandra(parsedArgs);


              // this should reload the config with cassandra available.
              LogManager.getFactory().removeContext(LogManager.getContext(false));

              // now we are allowed to look at log4j2.xml
              log = LogManager.getLogger();

              Properties sysProps = System.getProperties();
              for (Object prop : sysProps.keySet()) {
                log.trace(prop + "=" + sysProps.get(prop));
              }

              if (javaConfig != null) {
                Plan p = loadJavaConfig(javaConfig);
                log.info("Activating Plan: {}", p.getName());
                p.activate();
              } else {
                System.out.println("Please specify the java config via -Djj.javaConfig=<location of jar file>");
                System.exit(1);
              }

              while (true) {
                try {
                  System.out.print(".");
                  Thread.sleep(5000);
                } catch (InterruptedException e) {

                  // Yeah, I know this isn't going to do anything right now.. Placeholder to remind me to implement a real
                  // graceful shutdown... also keeps IDE from complaining stop() isn't used.

                  e.printStackTrace();
                  Cassandra.stop();
                  System.exit(0);
                }
              }

            } catch (Exception e) {
              e.printStackTrace();
              log.fatal("CRASH and BURNED:", e);
            }
          }
        });
        // unfortunately due to the hackery necessary to get things playing nice with one-jar, the contextClassLoader
        // is now out of sync with the system class loader, which messes up the Reflections library. So hack on hack...
        // todo: document why this reflection is necessary (I suspect I had some sort of security manager issue?) or remove
        // otherwise it seems like the following would be fine:
        // contextClassLoaderFix.setContextClassLoader(ClassLoader.getSystemClassLoader());
        Field _f_contextClassLoader = Thread.class.getDeclaredField("contextClassLoader");
        _f_contextClassLoader.setAccessible(true);
        _f_contextClassLoader.set(contextClassLoaderFix, ClassLoader.getSystemClassLoader());
        contextClassLoaderFix.setDaemon(false); // keep the JVM running please
        contextClassLoaderFix.start();

      } catch (Exception e) {
        System.out.println("CRASH and BURNED before starting main thread:");
        e.printStackTrace();
      }
    }
  }

  private static void startCassandra(Map<String, Object> parsedArgs) {
    String cassandraHome = (String) parsedArgs.get("--cassandra-home");
    File cassandraDir = null;
    if (cassandraHome != null) {
      cassandraHome = cassandraHome.replaceFirst("^~", System.getProperty("user.home"));
      cassandraDir = new File(cassandraHome);
      if (!cassandraDir.isDirectory()) {
        System.err.println("\nERROR: --cassandra-home must specify a directory\n");
        System.exit(1);
      }
    }
    if (cassandraDir == null) {
      cassandraDir = new File(JJ_DIR + "/cassandra");
    }
    Cassandra.start(cassandraDir);
  }


  private static Plan loadJavaConfig(String javaConfig) throws InstantiationException, IllegalAccessException {
    ClassLoader onejarLoader = null;
    File file = new File(javaConfig);
    if (!file.exists()) {
      System.err.println("File not found:" + file);
      System.exit(1);
    }

    try {
      File jarfile = new File(javaConfig);
      URL url = jarfile.toURI().toURL();

      ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
      onejarLoader = systemClassLoader;

      // This relies on us wrapping onejar's loader in a URL loader so we can add stuff.
      URLClassLoader classLoader = (URLClassLoader) systemClassLoader;
      Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
      method.setAccessible(true);
      method.invoke(classLoader, url);
    } catch (Exception ex) {
      ex.printStackTrace();
    }

    // Unfortunately this classpath scan adds quite a bit to startup time.... It seems to scan all the
    // Jdk classes (but not classes loaded by onejar, thank goodness) It only works with URLClassLoaders
    // but perhaps we can provide a temporary sub-class
    Reflections reflections = new Reflections(new ConfigurationBuilder().addUrls(ClasspathHelper.forClassLoader(onejarLoader)));
    ArrayList<Class> planProducers = new ArrayList<>(reflections.getTypesAnnotatedWith(JavaPlanConfig.class));

    if (log != null) {
      // can be null when outputting a visualization
      log.info("Found the following @JavaPlanConfig classes (first in list will be used):{}", planProducers);
    } else {
      System.out.println("Found the following @JavaPlanConfig classes (first in list will be used):" + planProducers);
    }
    Class config = planProducers.get(0);
    PlanProvider provider = (PlanProvider) config.newInstance();
    return provider.getPlan();
  }

  // will come back in some form when we serialize config to a file..

//  private static void writeConfig(Plan myPlan, String groupId) {
//    // This ~/.jj/groups is going to be the default location for loadable configs
//    // if the commandline startup id matches the name of a directory in the groups directory
//    // that configuration will be loaded.
//    String sep = System.getProperty("file.separator");
//    File jjConfigDir = new File(JJ_DIR, "groups" + sep + groupId + sep + myPlan.getName());
//    if (jjConfigDir.exists() || jjConfigDir.mkdirs()) {
//      System.out.println("made directories");
//      PlanImpl.Builder tmpBuilder = new PlanImpl.Builder();
//      String yaml = tmpBuilder.toYaml(myPlan);
//      System.out.println("created yaml string");
//      File file = new File(jjConfigDir, "config.jj");
//      try (FileOutputStream fis = new FileOutputStream(file)) {
//        fis.write(yaml.getBytes("UTF-8"));
//        System.out.println("created file");
//      } catch (IOException e) {
//        log.error("failed to write file", e);
//        throw new RuntimeException(e);
//      }
//    } else {
//      throw new RuntimeException("Failed to make config directories");
//    }
//  }

  /**
   * Set up security policy that allows RMI and JINI code to work. Also seems to be
   * helpful for running embedded cassandra. TODO: Minimize the permissions granted.
   */
  private static void initRMI() {
    // must do this before any jini code
    String policyFile = System.getProperty("java.security.policy");
    if (policyFile == null) {
      Policy.setPolicy(new JesterjPolicy());
    }
    System.setSecurityManager(new SecurityManager());
  }

  /**
   * Initialize the classloader. This method fixes up an issue with OneJar's class loaders. Nothing in or before
   * this method should touch logging, or 3rd party jars that logging that might try to setup log4j.
   *
   * @throws NoSuchFieldException   if the system class loader field has changed in this version of java and is not "scl"
   * @throws IllegalAccessException if we are unable to set the system class loader
   */
  private static void initClassloader() throws NoSuchFieldException, IllegalAccessException {
    // for river
    System.setProperty("java.rmi.server.RMIClassLoaderSpi", "net.jini.loader.pref.PreferredClassProvider");

    // fix bug in One-Jar with an ugly hack
    ClassLoader myClassLoader = Main.class.getClassLoader();
    String name = myClassLoader.getClass().getName();
    if ("com.simontuffs.onejar.JarClassLoader".equals(name)) {
      Field scl = ClassLoader.class.getDeclaredField("scl"); // Get system class loader
      scl.setAccessible(true); // Set accessible
      scl.set(null, new URLClassLoader(new URL[]{}, myClassLoader)); // Update it to our class loader
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  private static Map<String, Object> usage(String[] args) throws IOException {
    URL usage = Resources.getResource("usage.docopts.txt");
    String usageStr = Resources.toString(usage, Charset.forName("UTF-8"));
    Map<String, Object> result = new Docopt(usageStr).parse(args);
    System.out.println("\nReceived arguments:");
    for (String s : result.keySet()) {
      System.out.printf("   %s:%s\n", s, result.get(s));
    }
    if ((boolean) result.get("--help")) {
      System.out.println(usageStr);
      System.exit(1);
    }
    return result;
  }

  /**
   * This is a heuristic test for system shutdown. It is potentially expensive, so it should only be used in
   * code that is not performance sensitive. (i.e. code where an exception is already being thrown).
   *
   * @return true if the system is shutting down
   */

  public static boolean isNotShuttingDown() {
    try {
      Runtime.getRuntime().addShutdownHook(DUMMY_HOOK);
      Runtime.getRuntime().removeShutdownHook(DUMMY_HOOK);
    } catch (IllegalStateException e) {
      return false;
    }

    return true;
  }
}


