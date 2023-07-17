/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.maven.springcloud;

import com.microsoft.azure.maven.AbstractAzureMojo;
import com.microsoft.azure.maven.springcloud.config.AppDeploymentMavenConfig;
import com.microsoft.azure.maven.springcloud.config.ConfigurationParser;
import com.microsoft.azure.toolkit.lib.common.proxy.ProxyManager;
import com.microsoft.azure.toolkit.lib.springcloud.config.SpringCloudAppConfig;
import com.microsoft.azure.toolkit.lib.springcloud.config.SpringCloudClusterConfig;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Settings;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

public abstract class AbstractMojoBase extends AbstractAzureMojo {
    private static final String PROXY = "proxy";
    public static final String TELEMETRY_KEY_PUBLIC = "public";
    public static final String TELEMETRY_KEY_RUNTIME_VERSION = "runtimeVersion";
    public static final String TELEMETRY_KEY_CPU = "cpu";
    public static final String TELEMETRY_KEY_MEMORY = "memory";
    public static final String TELEMETRY_KEY_INSTANCE_COUNT = "instanceCount";
    public static final String TELEMETRY_KEY_JVM_OPTIONS = "jvmOptions";
    public static final String TELEMETRY_KEY_WITHIN_PARENT_POM = "isExecutedWithinParentPom";
    public static final String TELEMETRY_KEY_SUBSCRIPTION_ID = "subscriptionId";
    public static final String TELEMETRY_KEY_JAVA_VERSION = "javaVersion";

    /**
     * Whether user modify their pom file with azure-spring:config
     */
    public static final String TELEMETRY_KEY_POM_FILE_MODIFIED = "isPomFileModified";

    public static final String TELEMETRY_KEY_AUTH_METHOD = "authMethod";

    public static final String TELEMETRY_VALUE_AUTH_POM_CONFIGURATION = "Pom Configuration";
    public static final String TELEMETRY_KEY_PLUGIN_NAME = "pluginName";
    public static final String TELEMETRY_KEY_PLUGIN_VERSION = "pluginVersion";

    // region for cluster configuration
    /**
     * Name of the spring apps
     */
    @Getter
    @Parameter(property = "clusterName")
    protected String clusterName;

    /**
     * Region of the spring apps
     */
    @Getter
    @Parameter(property = "region")
    protected String region;

    /**
     * SKU of the spring apps, valid values are
     */
    @Getter
    @Parameter(property = "sku")
    protected String sku;

//    /**
//     * App environment of the spring apps, which will host your apps and microservices in the same
//     * environment with unified communication, observability, and network isolation.
//     * Valid for Consumption sku only
//     */
//    @Getter
//    @Parameter(property = "environment")
//    protected String environment;
//
//    /**
//     * Resource group of the app environment
//     */
//    @Getter
//    @Parameter(property = "environmentResourceGroup")
//    protected String environmentResourceGroup;
    // end of region

    /**
     * Boolean flag to control whether the app exposes public endpoint
     */
    @Getter
    @Parameter(alias = "public")
    protected Boolean isPublic;

    /**
     * Name of the resource group
     */
    @Getter
    @Parameter(property = "resourceGroup")
    protected String resourceGroup; // optional


    /**
     * Name of the Spring app. It will be created if not exist
     */
    @Getter
    @Parameter(property = "appName")
    protected String appName;

    /**
     * Runtime version of the Spring app, supported values are `Java 11`, `Java 17` and `Java 8`
     */
    @Getter
    @Parameter(property = "runtimeVersion")
    protected String runtimeVersion;

    /**
     * Configuration for spring app deployment*
     * Parameters for deployment
     * <ul>
     *     <li>cpu: Core numbers for deployment. </li>
     *     <li>memoryInGB: Memory for deployment. </li>
     *     <li>instanceCount: Max replicas num for apps of standard consumption plan or instance num for apps of other plans. </li>
     *     <li>deploymentName: Name for deployment. </li>
     *     <li>jvmOptions: JVM options for the deployed app. </li>
     *     <li>runtimeVersion: The runtime version for Spring app,  supported values are `Java 11`, `Java 17` and `Java 8`. </li>
     *     <li>enablePersistentStorage: Boolean flag to control whether or not to mount a persistent storage to /persistent folder(volume quota of 50 GB). </li>
     *     <li>environment: Environment variables for deployment. </li>
     *     <li>resources: Configuration to specify the artifacts to deploy
     *     <ul>
     *         <li>directory: Specifies where the resources are stored.</li>
     *         <li>includes: A list of patterns to include, e.g. '*.jar'.</li>
     *         <li>excludes: A list of patterns to exclude, e.g. '*.xml'.</li>
     *     </ul>
     *     </li>
     * </ul>
     */
    @Getter
    @Parameter(property = "deployment")
    protected AppDeploymentMavenConfig deployment;

    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    @Nullable
    private SpringCloudAppConfig configuration;

    protected void initTelemetryProxy() {
        super.initTelemetryProxy();
        final SpringCloudAppConfig configuration = this.getConfiguration();
        final String javaVersion = String.format("%s %s", System.getProperty("java.vendor"), System.getProperty("java.version"));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_PLUGIN_NAME, plugin.getArtifactId());
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_PLUGIN_VERSION, plugin.getVersion());
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_WITHIN_PARENT_POM, String.valueOf(project.getPackaging().equalsIgnoreCase("pom")));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_JAVA_VERSION, javaVersion);

        telemetryProxy.addDefaultProperty(PROXY, String.valueOf(ProxyManager.getInstance().isProxyEnabled()));

        // Todo update deploy mojo telemetries with real value
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_AUTH_METHOD, TELEMETRY_VALUE_AUTH_POM_CONFIGURATION);

        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_PUBLIC, String.valueOf(configuration.isPublic()));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_RUNTIME_VERSION, configuration.getDeployment().getRuntimeVersion());
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_CPU, String.valueOf(configuration.getDeployment().getCpu()));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_MEMORY, String.valueOf(configuration.getDeployment().getMemoryInGB()));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_INSTANCE_COUNT, String.valueOf(configuration.getDeployment().getCapacity()));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_JVM_OPTIONS,
                String.valueOf(StringUtils.isEmpty(configuration.getDeployment().getJvmOptions())));
        telemetryProxy.addDefaultProperty(TELEMETRY_KEY_SUBSCRIPTION_ID, Optional.ofNullable(configuration.getCluster()).map(SpringCloudClusterConfig::getSubscriptionId).orElse(StringUtils.EMPTY));
    }

    public synchronized SpringCloudAppConfig getConfiguration() {
        if (Objects.isNull(this.configuration)) {
            final ConfigurationParser parser = ConfigurationParser.getInstance();
            this.configuration = parser.parse(this);
        }
        return this.configuration;
    }
}
