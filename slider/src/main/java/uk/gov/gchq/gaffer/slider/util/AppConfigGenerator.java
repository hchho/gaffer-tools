/*
 * Copyright 2017 Crown Copyright
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

package uk.gov.gchq.gaffer.slider.util;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnClusterMetrics;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.log4j.Logger;
import org.apache.slider.api.ResourceKeys;
import org.apache.slider.core.conf.ConfTree;
import org.apache.slider.core.persist.ConfTreeSerDeser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This utility will auto-generate configuration files (based on a template) that can be used with Apache Slider to
 * deploy Gaffer onto an existing cluster managed by YARN. The configuration will be generated so that the deployed
 * Gaffer instance will use a specified proportion (default: 85%) of the resources (cpu, mem) available via YARN.
 * <p>
 * To run:
 * <pre>
 * {@code java -cp slider-$VERSION.jar:<slider-install>/lib/*:$(hadoop classpath) \
 *   uk.gov.gchq.gaffer.slider.util.AppConfigGenerator appConfig-default.json appConfig.json resources.json}
 * </pre>
 */
public class AppConfigGenerator implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(AppConfigGenerator.class);

    static class SliderAppConfig {

        private ConfTree appConfig;
        private ConfTree resources;

        SliderAppConfig(final ConfTree appConfig, final ConfTree resources) {
            this.appConfig = appConfig;
            this.resources = resources;
        }

        ConfTree getAppConfig() {
            return this.appConfig;
        }

        ConfTree getResources() {
            return this.resources;
        }

    }

    static class AvailableResources {

        private int maxCores;
        private int maxMemory;
        private int nodeCount;

        AvailableResources(final int maxCores, final int maxMemory, final int nodeCount) {
            this.maxCores = maxCores;
            this.maxMemory = maxMemory;
            this.nodeCount = nodeCount;
        }

        private int getMaxCores() {
            return this.maxCores;
        }

        private int getMaxMemory() {
            return this.maxMemory;
        }

        private int getNodeCount() {
            return this.nodeCount;
        }

        @Override
        public String toString() {
            return "AvailableResources{" +
                "maxCores=" + this.maxCores +
                ", maxMemory=" + this.maxMemory +
                ", nodeCount=" + this.nodeCount +
                '}';
        }

    }

    enum COMPONENT {
        ACCUMULO_MASTER,
        ACCUMULO_TSERVER,
        ACCUMULO_MONITOR,
        ACCUMULO_GC,
        ACCUMULO_TRACER,
        ACCUMULO_PROXY
    }

    private static Map<COMPONENT, String> componentToPropertyLookup = new HashMap<>();

    static {
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_MASTER, "site.accumulo-env.master_heapsize");
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_TSERVER, "site.accumulo-env.tserver_heapsize");
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_MONITOR, "site.accumulo-env.monitor_heapsize");
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_GC, "site.accumulo-env.gc_heapsize");
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_TRACER, "site.accumulo-env.other_heapsize");
        componentToPropertyLookup.put(COMPONENT.ACCUMULO_PROXY, "site.accumulo-env.other_heapsize");
    }

    // Command line argument parsing

    @Parameter(names = "-t", description = "The number of tablet servers to deploy per YARN Node")
    private int tserversPerNode = 1;

    @Parameter(names = { "-c", "--cores" }, description = "The number of cores to be used by each non-tablet server component")
    private int componentCores = 1;

    @Parameter(names = { "-m", "--mem" }, description = "The default amount of memory to be used by each component instance (in MB)")
    private int defaultComponentMemory = 1024;

    @Parameter(names = { "-u", "--usage" }, description = "The proportion of the cluster resources this application should be configured to use (as a percentage)")
    private int clusterUsagePercent = 85;

    @Parameter(names = "-s", description = "Generate the allocation so that all components could fit on a single node, otherwise the allocation will try to use as much of the resources available across the cluster as possible")
    private boolean singleNode = false;

    @Parameter(names = { "-h", "--help"}, description = "Displays this help text", help = true)
    private boolean help = false;

    @Parameter(description = "<appConfigTemplate> <appConfigOutputPath> <resourcesOutputPath>")
    private List<String> files = new ArrayList<>();

    private String initialAppConfigPath;

    private String appConfigOutputPath;

    private String resourcesOutputPath;

    public void setTserversPerNode(final int tserversPerNode) {
        this.tserversPerNode = tserversPerNode;
    }

    public void setComponentCores(final int componentCores) {
        this.componentCores = componentCores;
    }

    public void setDefaultComponentMemory(final int defaultComponentMemory) {
        this.defaultComponentMemory = defaultComponentMemory;
    }

    public void setClusterUsagePercent(final int clusterUsagePercent) {
        this.clusterUsagePercent = clusterUsagePercent;
    }

    public void setSingleNode(final boolean singleNode) {
        this.singleNode = singleNode;
    }

    private void validateArguments() throws Exception {
        this.help = true;

        if (this.tserversPerNode <= 0) {
            throw new Exception("A minimum of 1 tablet server must be provisioned on each node!");
        } else if (this.componentCores <= 0) {
            throw new Exception("Each component must be provisioned with at least 1 core!");
        } else if (this.defaultComponentMemory <= 0) {
            throw new Exception("Components can't be provisioned with a negative amount of memory!");
        } else if (this.clusterUsagePercent <= 0 || this.clusterUsagePercent > 100) {
            throw new Exception("Cluster usage must be provided as a percentage!");
        } else if (this.files.size() != 3) {
            throw new Exception("Invalid number of arguments!");
        } else {
            this.initialAppConfigPath = this.files.get(0);
            this.appConfigOutputPath = this.files.get(1);
            this.resourcesOutputPath = this.files.get(2);

            this.help = false;
        }
    }

    private int convertHeapSizePropertyToNumBytes(final String value) {
        if (value.endsWith("g")) {
            return Integer.parseInt(value.substring(0, value.length() - 1)) * 1024;
        } else if (value.endsWith("m")) {
            return Integer.parseInt(value.substring(0, value.length() - 1));
        }

        throw new NumberFormatException(String.format("Unable to convert %s to a number", value));
    }

    private AvailableResources getYarnResources() throws IOException, YarnException {
        Configuration config = new Configuration();
        YarnClient yarn = YarnClient.createYarnClient();
        yarn.init(config);
        yarn.start();

        // Query YARN to find out the largest container it is capable of scheduling
        YarnClientApplication app = yarn.createApplication();
        Resource resources = app.getNewApplicationResponse().getMaximumResourceCapability();

        // Also find out how many nodes there are in the cluster by asking for the number of registered Node Managers
        YarnClusterMetrics metrics = yarn.getYarnClusterMetrics();

        yarn.close();

        return new AvailableResources(resources.getVirtualCores(), resources.getMemory(), metrics.getNumNodeManagers());
    }

    /**
     * Calculates how many cores and how much memory should be requested by each Accumulo Tablet server, so that as much
     * of the cpu and mem available in the cluster is used as possible. Note that in most cases this means your Accumulo
     * instance will be unable to tolerate the loss of any YARN Node Managers.
     * @param app Current application config
     * @param availableResources Resources (cpu, mem, nodes) available in the YARN cluster
     * @return SliderAppConfig modified with the cpu and mem that each tablet server should request
     * @throws IOException Not enough resources available to be split across all the requested tablet servers
     */
    private SliderAppConfig generateSliderAppConfigForMultiNode(final SliderAppConfig app, final AvailableResources availableResources) throws IOException {
        ConfTree appConfig = app.getAppConfig();
        ConfTree resources = app.getResources();

        int totalCoresAvailable = availableResources.getMaxCores() * availableResources.getNodeCount();
        int totalMemoryAvailable = availableResources.getMaxMemory() * availableResources.getNodeCount();

        totalCoresAvailable = Math.round((float) totalCoresAvailable * ((float) this.clusterUsagePercent / 100f));
        totalMemoryAvailable = Math.round((float) totalMemoryAvailable * ((float) this.clusterUsagePercent / 100f));
        LOGGER.info(String.format("Trying to use %s%% of available resources across cluster = cores: %s mem: %s", this.clusterUsagePercent, totalCoresAvailable, totalMemoryAvailable));

        // Slider Application Master
        totalCoresAvailable -= ResourceKeys.DEF_YARN_CORES;
        totalMemoryAvailable -= ResourceKeys.DEF_YARN_MEMORY;

        // Accumulo Components
        for (String componentName : resources.components.keySet()) {
            if (!componentName.equals(COMPONENT.ACCUMULO_TSERVER.name())) {
                Map<String, String> componentConfig = resources.components.get(componentName);
                int instanceCount = Integer.parseInt(componentConfig.get(ResourceKeys.COMPONENT_INSTANCES));
                int cores = Integer.parseInt(componentConfig.get(ResourceKeys.YARN_CORES));
                int memory = Integer.parseInt(componentConfig.get(ResourceKeys.YARN_MEMORY));

                totalCoresAvailable -= cores * instanceCount;
                totalMemoryAvailable -= memory * instanceCount;
            }
        }

        if (totalCoresAvailable <= 0 || totalMemoryAvailable <= 0) {
            throw new IOException(String.format("No resources left for any tablet servers! cores: %s memory: %s", totalCoresAvailable, totalMemoryAvailable));
        }

        int tserverCores = totalCoresAvailable / (this.tserversPerNode * availableResources.getNodeCount());
        int tserverMemory = totalMemoryAvailable / (this.tserversPerNode * availableResources.getNodeCount());

        if (tserverCores <= 0 || tserverMemory <= 0) {
            throw new IOException(String.format("Not enough available resources to deploy %s tablet servers per node, only cores: %s memory: %s available across the cluster!", this.tserversPerNode, totalCoresAvailable, totalMemoryAvailable));
        }

        Map<String, String> tabletServerConfig = resources.components.get(COMPONENT.ACCUMULO_TSERVER.name());
        tabletServerConfig.put(ResourceKeys.COMPONENT_INSTANCES, String.valueOf(availableResources.getNodeCount() * this.tserversPerNode));
        tabletServerConfig.put(ResourceKeys.YARN_CORES, String.valueOf(tserverCores));
        tabletServerConfig.put(ResourceKeys.YARN_MEMORY, String.valueOf(tserverMemory));

        appConfig.global.put(componentToPropertyLookup.get(COMPONENT.ACCUMULO_TSERVER), String.valueOf(tserverMemory) + "m");

        return app;
    }

    /**
     * Calculates how many cores and how much memory should be requested by each Accumulo Tablet server, so that (if
     * required) all Accumulo components could be deployed on a single YARN node. This ensures that, as long as there is
     * at least one YARN Node Manager available, it will be possible for your Accumulo instance to be deployed.
     * (NB: This only holds if all the Node Managers in your YARN cluster have the same availability of cpu and mem)
     * @param app Current application config
     * @param availableResources Resources (cpu, mem, nodes) available in the YARN cluster
     * @return SliderAppConfig modified with the cpu and mem that each tablet server should request
     * @throws IOException Not enough resources available to be split across all the requested tablet servers
     */
    private SliderAppConfig generateSliderAppConfigForSingleNode(final SliderAppConfig app, final AvailableResources availableResources) throws IOException {
        ConfTree appConfig = app.getAppConfig();
        ConfTree resources = app.getResources();

        int coresRemainingPerNode = Math.round((float) availableResources.getMaxCores() * ((float) this.clusterUsagePercent / 100f));
        int memoryRemainingPerNode = Math.round((float) availableResources.getMaxMemory() * ((float) this.clusterUsagePercent / 100f));
        LOGGER.info(String.format("Trying to use %s%% of available resources per node = cores: %s mem: %s", this.clusterUsagePercent, coresRemainingPerNode, memoryRemainingPerNode));

        // Slider Application Master
        coresRemainingPerNode -= ResourceKeys.DEF_YARN_CORES;
        memoryRemainingPerNode -= ResourceKeys.DEF_YARN_MEMORY;

        // Accumulo Components
        for (String componentName : resources.components.keySet()) {
            if (!componentName.equals(COMPONENT.ACCUMULO_TSERVER.name())) {
                Map<String, String> componentConfig = resources.components.get(componentName);
                int instanceCount = Integer.parseInt(componentConfig.get(ResourceKeys.COMPONENT_INSTANCES));
                int cores = Integer.parseInt(componentConfig.get(ResourceKeys.YARN_CORES));
                int memory = Integer.parseInt(componentConfig.get(ResourceKeys.YARN_MEMORY));

                coresRemainingPerNode -= cores * instanceCount;
                memoryRemainingPerNode -= memory * instanceCount;
            }
        }

        if (coresRemainingPerNode <= 0 || memoryRemainingPerNode <= 0) {
            throw new IOException(String.format("No resources left for any tablet servers! cores: %s memory: %s", coresRemainingPerNode, memoryRemainingPerNode));
        }

        int tserverCores = coresRemainingPerNode / (this.tserversPerNode * availableResources.getNodeCount());
        int tserverMemory = memoryRemainingPerNode / (this.tserversPerNode * availableResources.getNodeCount());

        if (tserverCores <= 0 || tserverMemory <= 0) {
            throw new IOException(String.format("Not enough available resources to deploy %s tablet servers per node, only cores: %s memory: %s available per node!", this.tserversPerNode, coresRemainingPerNode, memoryRemainingPerNode));
        }

        Map<String, String> tabletServerConfig = resources.components.get(COMPONENT.ACCUMULO_TSERVER.name());
        tabletServerConfig.put(ResourceKeys.COMPONENT_INSTANCES, String.valueOf(availableResources.getNodeCount() * this.tserversPerNode));
        tabletServerConfig.put(ResourceKeys.YARN_CORES, String.valueOf(tserverCores));
        tabletServerConfig.put(ResourceKeys.YARN_MEMORY, String.valueOf(tserverMemory));

        appConfig.global.put(componentToPropertyLookup.get(COMPONENT.ACCUMULO_TSERVER), String.valueOf(tserverMemory) + "m");

        return app;
    }

    public SliderAppConfig generateSliderAppConfig(final ConfTree appConfig, final AvailableResources availableResources) throws IOException {
        ConfTree resources = new ConfTree();

        // Generate baseline YARN resource config for each Accumulo component
        for (int i = 0; i < COMPONENT.values().length; i++) {
            COMPONENT component = COMPONENT.values()[i];

            Map<String, String> componentConfig = new HashMap<>();
            componentConfig.put(ResourceKeys.COMPONENT_INSTANCES, "1");
            componentConfig.put(ResourceKeys.COMPONENT_PRIORITY, String.valueOf(component.ordinal() + 1));
            componentConfig.put(ResourceKeys.YARN_CORES, String.valueOf(this.componentCores));

            // Start with the default memory usage for each non-tablet server component
            int componentMemory = this.defaultComponentMemory;
            // Infer how much memory is required for the component based on what its heapsize is set to
            String propertyName = componentToPropertyLookup.get(component);
            if (appConfig.global.containsKey(propertyName)) {
                String propertyValue = appConfig.global.get(propertyName);
                componentMemory = this.convertHeapSizePropertyToNumBytes(propertyValue);
            }
            componentConfig.put(ResourceKeys.YARN_MEMORY, String.valueOf(componentMemory));

            resources.components.put(component.name(), componentConfig);
        }

        // Two possible resource allocation schemes for Tablet Servers:
        if (this.singleNode) {
            return this.generateSliderAppConfigForSingleNode(new SliderAppConfig(appConfig, resources), availableResources);
        } else {
            return this.generateSliderAppConfigForMultiNode(new SliderAppConfig(appConfig, resources), availableResources);
        }
    }

    @Override
    public void run() {
        try {
            ConfTreeSerDeser parser = new ConfTreeSerDeser();

            ConfTree initialAppConfig = parser.fromFile(new File(this.initialAppConfigPath));
            LOGGER.info("Initial appConfig.json:");
            LOGGER.info(initialAppConfig);

            AvailableResources availableClusterResources = null;

            availableClusterResources = this.getYarnResources();
            LOGGER.info("Available Cluster Resources:" + System.currentTimeMillis());
            LOGGER.info(availableClusterResources);

            // We query twice because for some reason YARN on EMR lies about the max resources
            // available per node the first time round :S
            // TODO: Work out why this is the case!
            availableClusterResources = this.getYarnResources();
            LOGGER.info("Available Cluster Resources:" + System.currentTimeMillis());
            LOGGER.info(availableClusterResources);

            SliderAppConfig config = this.generateSliderAppConfig(initialAppConfig, availableClusterResources);
            LOGGER.info("Generated appConfig.json:");
            LOGGER.info(config.getAppConfig());
            LOGGER.info("Generated resources.json:");
            LOGGER.info(config.getResources());

            parser.save(config.getAppConfig(), new File(this.appConfigOutputPath));
            parser.save(config.getResources(), new File(this.resourcesOutputPath));
        } catch (YarnException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(final String[] args) {
        AppConfigGenerator generator = new AppConfigGenerator();

        JCommander argParser = new JCommander(generator, args);
        argParser.setProgramName(AppConfigGenerator.class.getSimpleName());

        try {
            generator.validateArguments();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }

        if (generator.help) {
            argParser.usage();
            System.exit(1);
        } else {
            generator.run();
        }
    }

}