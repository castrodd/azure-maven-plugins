/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.function;

import com.azure.resourcemanager.appservice.AppServiceManager;
import com.azure.resourcemanager.appservice.fluent.WebAppsClient;
import com.azure.resourcemanager.appservice.fluent.models.SiteConfigResourceInner;
import com.azure.resourcemanager.appservice.models.FunctionApp.DefinitionStages;
import com.azure.resourcemanager.appservice.models.FunctionApp.Update;
import com.microsoft.azure.toolkit.lib.appservice.model.ContainerAppFunctionConfiguration;
import com.microsoft.azure.toolkit.lib.appservice.model.DiagnosticConfig;
import com.microsoft.azure.toolkit.lib.appservice.model.DockerConfiguration;
import com.microsoft.azure.toolkit.lib.appservice.model.FlexConsumptionConfiguration;
import com.microsoft.azure.toolkit.lib.appservice.model.FunctionAppLinuxRuntime;
import com.microsoft.azure.toolkit.lib.appservice.model.FunctionAppRuntime;
import com.microsoft.azure.toolkit.lib.appservice.model.OperatingSystem;
import com.microsoft.azure.toolkit.lib.appservice.model.PricingTier;
import com.microsoft.azure.toolkit.lib.appservice.model.Runtime;
import com.microsoft.azure.toolkit.lib.appservice.plan.AppServicePlan;
import com.microsoft.azure.toolkit.lib.appservice.utils.AppServiceUtils;
import com.microsoft.azure.toolkit.lib.common.action.Action;
import com.microsoft.azure.toolkit.lib.common.action.AzureActionManager;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.operation.OperationContext;
import com.microsoft.azure.toolkit.lib.containerapps.environment.ContainerAppsEnvironment;
import com.microsoft.azure.toolkit.lib.storage.StorageAccount;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class FunctionAppDraft extends FunctionApp implements AzResource.Draft<FunctionApp, com.azure.resourcemanager.appservice.models.FunctionApp> {
    private static final String CREATE_NEW_FUNCTION_APP = "isCreateNewFunctionApp";
    public static final String FUNCTIONS_EXTENSION_VERSION = "FUNCTIONS_EXTENSION_VERSION";
    public static final String UNSUPPORTED_OPERATING_SYSTEM = "Unsupported operating system %s";
    public static final String CAN_NOT_UPDATE_EXISTING_APP_SERVICE_OS = "Can not update the operation system of an existing app";

    public static final String APP_SETTING_MACHINEKEY_DECRYPTION_KEY = "MACHINEKEY_DecryptionKey";
    public static final String APP_SETTING_WEBSITES_ENABLE_APP_SERVICE_STORAGE = "WEBSITES_ENABLE_APP_SERVICE_STORAGE";
    public static final String APP_SETTING_DISABLE_WEBSITES_APP_SERVICE_STORAGE = "false";
    public static final String APP_SETTING_FUNCTION_APP_EDIT_MODE = "FUNCTION_APP_EDIT_MODE";
    public static final String APP_SETTING_FUNCTION_APP_EDIT_MODE_READONLY = "readOnly";
    public static final String APPLICATIONINSIGHTS_ENABLE_AGENT = "APPLICATIONINSIGHTS_ENABLE_AGENT";
    public static final String SERVICE_PLAN_MISSING_MESSAGE = "'service plan' is required to create a Function App";
    public static final String UNSUPPORTED_OPERATING_SYSTEM_FOR_CONTAINER_APP = "Unsupported operating system %s for function app on container app";

    @Getter
    @Nullable
    private final FunctionApp origin;
    @Nullable
    private Config config;

    FunctionAppDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull FunctionAppModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    FunctionAppDraft(@Nonnull FunctionApp origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.config = null;
    }

    @Nonnull
    private synchronized Config ensureConfig() {
        this.config = Optional.ofNullable(this.config).orElseGet(Config::new);
        return this.config;
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/function.create_app.app", params = {"this.getName()"})
    public com.azure.resourcemanager.appservice.models.FunctionApp createResourceInAzure() {
        Runtime.tryWarningDeprecation(this);
        OperationContext.action().setTelemetryProperty(CREATE_NEW_FUNCTION_APP, String.valueOf(true));
        OperationContext.action().setTelemetryProperty("subscriptionId", getSubscriptionId());
        OperationContext.action().setTelemetryProperty("useEnvironment", String.valueOf(Objects.nonNull(getEnvironment())));
        Optional.ofNullable(getRegion()).ifPresent(region -> OperationContext.action().setTelemetryProperty("region", region.getLabel()));
        Optional.ofNullable(getRuntime()).ifPresent(runtime -> OperationContext.action().setTelemetryProperty("runtime", runtime.getDisplayName()));
        Optional.ofNullable(getRuntime()).map(Runtime::getOperatingSystem).ifPresent(os -> OperationContext.action().setTelemetryProperty("os", os.getValue()));
        Optional.ofNullable(getRuntime()).map(Runtime::getJavaVersionUserText).ifPresent(javaVersion -> OperationContext.action().setTelemetryProperty("javaVersion", javaVersion));

        final String name = getName();
        final FunctionAppRuntime newRuntime = Objects.requireNonNull(getRuntime(), "'runtime' is required to create a Function App");
        @Nullable final AppServicePlan newPlan = getAppServicePlan();
        final ContainerAppsEnvironment environment = getEnvironment();
        final Map<String, String> newAppSettings = getAppSettings();
        final DiagnosticConfig newDiagnosticConfig = getDiagnosticConfig();
        final FlexConsumptionConfiguration newFlexConsumptionConfiguration = getFlexConsumptionConfiguration();
        final String funcExtVersion = Optional.ofNullable(newAppSettings).map(map -> map.get(FUNCTIONS_EXTENSION_VERSION)).orElse(null);
        final StorageAccount storageAccount = getStorageAccount();

        final AppServiceManager manager = Objects.requireNonNull(this.getParent().getRemote());
        final DefinitionStages.Blank blank = manager.functionApps().define(name);
        final DefinitionStages.WithCreate withCreate;
        if (Objects.nonNull(environment)) {
            // container app based function app
            final ContainerAppFunctionConfiguration containerConfiguration = getContainerConfiguration();
            final DefinitionStages.WithScaleRulesOrDockerContainerImage withImage = blank
                .withRegion(Objects.requireNonNull(getRegion(), "'region' is required to create a container based function app").getName())
                .withExistingResourceGroup(getResourceGroupName())
                .withManagedEnvironmentId(environment.getId());
            Optional.ofNullable(containerConfiguration).map(ContainerAppFunctionConfiguration::getMaxReplicas).ifPresent(withImage::withMaxReplicas);
            Optional.ofNullable(containerConfiguration).map(ContainerAppFunctionConfiguration::getMinReplicas).ifPresent(withImage::withMinReplicas);
            if (newRuntime.getOperatingSystem() != OperatingSystem.DOCKER && newRuntime.getOperatingSystem() != OperatingSystem.LINUX) {
                throw new AzureToolkitRuntimeException(String.format(UNSUPPORTED_OPERATING_SYSTEM_FOR_CONTAINER_APP, newRuntime.getOperatingSystem()));
            }
            withCreate = newRuntime.getOperatingSystem() == OperatingSystem.DOCKER ?
                defineDockerContainerImage(withImage) :
                withImage.withBuiltInImage(((FunctionAppLinuxRuntime)newRuntime).toFunctionRuntimeStack(funcExtVersion));
        } else {
            // normal function app
            final OperatingSystem os = newRuntime.isDocker() ? OperatingSystem.LINUX : newRuntime.getOperatingSystem();
            if (os != Objects.requireNonNull(newPlan, SERVICE_PLAN_MISSING_MESSAGE).getOperatingSystem()) {
                throw new AzureToolkitRuntimeException(String.format("Could not create %s app service in %s service plan", newRuntime.getOperatingSystem(), newPlan.getOperatingSystem()));
            }
            if (newRuntime.getOperatingSystem() == OperatingSystem.LINUX) {
                withCreate = blank.withExistingLinuxAppServicePlan(newPlan.getRemote())
                    .withExistingResourceGroup(getResourceGroupName())
                    .withBuiltInImage(((FunctionAppLinuxRuntime)newRuntime).toFunctionRuntimeStack(funcExtVersion));
            } else if (newRuntime.getOperatingSystem() == OperatingSystem.WINDOWS) {
                withCreate = (DefinitionStages.WithCreate) blank
                    .withExistingAppServicePlan(newPlan.getRemote())
                    .withExistingResourceGroup(getResourceGroupName())
                    .withJavaVersion(newRuntime.getJavaVersion())
                    .withWebContainer(null);
            } else if (newRuntime.getOperatingSystem() == OperatingSystem.DOCKER) {
                if (StringUtils.equalsIgnoreCase(newPlan.getPricingTier().getTier(), "Dynamic")) {
                    throw new AzureToolkitRuntimeException("Docker function is not supported in consumption service plan");
                }
                final DefinitionStages.WithDockerContainerImage withLinuxAppFramework = blank
                    .withExistingLinuxAppServicePlan(newPlan.getRemote())
                    .withExistingResourceGroup(getResourceGroupName());
                withCreate = defineDockerContainerImage(withLinuxAppFramework);
            } else {
                throw new AzureToolkitRuntimeException(String.format(UNSUPPORTED_OPERATING_SYSTEM, newRuntime.getOperatingSystem()));
            }
        }
        if (MapUtils.isNotEmpty(newAppSettings)) {
            // todo: support remove app settings
            withCreate.withAppSettings(newAppSettings);
        }
        if (Objects.nonNull(storageAccount)) {
            withCreate.withExistingStorageAccount(storageAccount.getRemote());
        }
        // diagnostic config is only available for service plan function apps
        if (Objects.nonNull(newDiagnosticConfig) && Objects.isNull(environment)) {
            AppServiceUtils.defineDiagnosticConfigurationForWebAppBase(withCreate, newDiagnosticConfig);
        }
        final Boolean enableDistributedTracing = ensureConfig().getEnableDistributedTracing();
        if (Objects.nonNull(enableDistributedTracing)) {
            withCreate.withAppSetting(APPLICATIONINSIGHTS_ENABLE_AGENT, String.valueOf(enableDistributedTracing));
        } else if (shouldEnableDistributedTracing(newPlan, newAppSettings)) {
            withCreate.withAppSetting(APPLICATIONINSIGHTS_ENABLE_AGENT, "true");
        }
        final IAzureMessager messager = AzureMessager.getMessager();
        final boolean isFlexConsumption = Optional.ofNullable(getAppServicePlan())
            .map(AppServicePlan::getPricingTier)
            .map(PricingTier::isFlexConsumption).orElse(false);
        final boolean updateFlexConsumptionConfiguration = isFlexConsumption && Objects.nonNull(newFlexConsumptionConfiguration);
        if (updateFlexConsumptionConfiguration) {
            withCreate.withContainerSize(newFlexConsumptionConfiguration.getInstanceSize());
            withCreate.withWebAppAlwaysOn(false);
        }
        messager.info(AzureString.format("Start creating Function App({0})...", name));
        com.azure.resourcemanager.appservice.models.FunctionApp functionApp = Objects.requireNonNull(this.doModify(() -> {
            com.azure.resourcemanager.appservice.models.FunctionApp app = withCreate.create();
            if (updateFlexConsumptionConfiguration) {
                updateFlexConsumptionConfiguration(app, newFlexConsumptionConfiguration);
            }
            return app;
        }, Status.CREATING));
        // deploy action did not fit docker runtime function app
        final Action<AzResource> deploy = getRuntime().isDocker() ? null : Optional.ofNullable(AzureActionManager.getInstance().getAction(AzResource.DEPLOY))
            .map(action -> action.bind(this)).orElse(null);
        messager.success(AzureString.format("Function App({0}) is successfully created", name), deploy);
        Optional.ofNullable(getEnvironment()).ifPresent(ContainerAppsEnvironment::refresh); // refresh container apps environment after create container hosted function app
        return functionApp;
    }

    private boolean shouldEnableDistributedTracing(@Nullable final AppServicePlan servicePlan, @Nullable final Map<String, String> appSettings) {
        final boolean isConsumptionPlan = !Objects.isNull(servicePlan) && servicePlan.getPricingTier().isConsumption();
        final boolean isSetInAppSettings = MapUtils.isNotEmpty(appSettings) && appSettings.containsKey(APPLICATIONINSIGHTS_ENABLE_AGENT);
        return !(isConsumptionPlan || isSetInAppSettings);
    }

    DefinitionStages.WithCreate defineDockerContainerImage(@Nonnull DefinitionStages.WithDockerContainerImage withLinuxAppFramework) {
        // check service plan, consumption is not supported
        final String message = "Docker configuration is required to create a docker based Function app";
        final DockerConfiguration config = Objects.requireNonNull(this.getDockerConfiguration(), message);
        final DefinitionStages.WithCreate draft;
        if (StringUtils.isAllEmpty(config.getUserName(), config.getPassword())) {
            draft = withLinuxAppFramework
                .withPublicDockerHubImage(config.getImage());
        } else if (StringUtils.isEmpty(config.getRegistryUrl())) {
            draft = withLinuxAppFramework
                .withPrivateDockerHubImage(config.getImage())
                .withCredentials(config.getUserName(), config.getPassword());
        } else {
            draft = withLinuxAppFramework
                .withPrivateRegistryImage(config.getImage(), config.getRegistryUrl())
                .withCredentials(config.getUserName(), config.getPassword());
        }
        final String decryptionKey = generateDecryptionKey();
        return (DefinitionStages.WithCreate) draft.withAppSetting(APP_SETTING_MACHINEKEY_DECRYPTION_KEY, decryptionKey)
            .withAppSetting(APP_SETTING_WEBSITES_ENABLE_APP_SERVICE_STORAGE, APP_SETTING_DISABLE_WEBSITES_APP_SERVICE_STORAGE)
            .withAppSetting(APP_SETTING_FUNCTION_APP_EDIT_MODE, APP_SETTING_FUNCTION_APP_EDIT_MODE_READONLY);
    }

    protected String generateDecryptionKey() {
        // Refers https://github.com/Azure/azure-cli/blob/dev/src/azure-cli/azure/cli/command_modules/appservice/custom.py#L2300
        return Hex.encodeHexString(RandomUtils.nextBytes(32)).toUpperCase();
    }

    @Nonnull
    @Override
    @AzureOperation(name = "azure/function.update_app.app", params = {"this.getName()"})
    public com.azure.resourcemanager.appservice.models.FunctionApp updateResourceInAzure(@Nonnull com.azure.resourcemanager.appservice.models.FunctionApp remote) {
        assert origin != null : "updating target is not specified.";
        Runtime.tryWarningDeprecation(this);
        final Map<String, String> oldAppSettings = Objects.requireNonNull(origin.getAppSettings());
        final Map<String, String> settingsToAdd = Optional.ofNullable(this.ensureConfig().getAppSettings()).orElseGet(HashMap::new);
        final Boolean enableDistributedTracing = ensureConfig().getEnableDistributedTracing();
        if (Objects.nonNull(enableDistributedTracing)) {
            settingsToAdd.put(APPLICATIONINSIGHTS_ENABLE_AGENT, String.valueOf(enableDistributedTracing));
        }
        if (ObjectUtils.allNotNull(oldAppSettings, settingsToAdd)) {
            settingsToAdd.entrySet().removeAll(oldAppSettings.entrySet());
        }
        final Set<String> settingsToRemove = Optional.ofNullable(this.ensureConfig().getAppSettingsToRemove())
            .map(set -> set.stream().filter(oldAppSettings::containsKey).collect(Collectors.toSet()))
            .orElse(Collections.emptySet());
        final DiagnosticConfig oldDiagnosticConfig = super.getDiagnosticConfig();
        final DiagnosticConfig newDiagnosticConfig = this.ensureConfig().getDiagnosticConfig();
        final Runtime newRuntime = this.ensureConfig().getRuntime();
        @Nullable final AppServicePlan newPlan = this.ensureConfig().getPlan();
        final DockerConfiguration newDockerConfig = this.ensureConfig().getDockerConfiguration();
        final FlexConsumptionConfiguration newFlexConsumptionConfiguration = this.ensureConfig().getFlexConsumptionConfiguration();
        final StorageAccount storageAccount = getStorageAccount();
        final Runtime oldRuntime = origin.getRuntime();
        final AppServicePlan oldPlan = origin.getAppServicePlan();
        final FlexConsumptionConfiguration oldFlexConsumptionConfiguration = origin.getFlexConsumptionConfiguration();

        final boolean planModified = Objects.nonNull(newPlan) && !Objects.equals(newPlan, oldPlan);
        final boolean runtimeModified = (Objects.isNull(oldRuntime) || !oldRuntime.isDocker()) &&
            Objects.nonNull(newRuntime) && !Objects.equals(newRuntime, oldRuntime);
        final boolean dockerModified = Objects.nonNull(oldRuntime) && oldRuntime.isDocker() &&
            Objects.nonNull(newDockerConfig);
        final boolean isFlexConsumption = Optional.ofNullable(getAppServicePlan())
            .map(AppServicePlan::getPricingTier).map(PricingTier::isFlexConsumption).orElse(false);
        final boolean flexConsumptionModified = isFlexConsumption &&
            Objects.nonNull(newFlexConsumptionConfiguration) && !newFlexConsumptionConfiguration.isEmpty() && !Objects.equals(newFlexConsumptionConfiguration, oldFlexConsumptionConfiguration);
        final boolean isAppSettingsModified = MapUtils.isNotEmpty(settingsToAdd) || CollectionUtils.isNotEmpty(settingsToRemove);
        final boolean isDiagnosticConfigModified = Objects.nonNull(newDiagnosticConfig) && !Objects.equals(newDiagnosticConfig, oldDiagnosticConfig);
        final boolean modified = planModified || runtimeModified || dockerModified || flexConsumptionModified ||
            isAppSettingsModified || Objects.nonNull(newDiagnosticConfig) || Objects.nonNull(storageAccount);
        final String funcExtVersion = Optional.of(settingsToAdd).map(map -> map.get(FUNCTIONS_EXTENSION_VERSION))
            .orElseGet(() -> oldAppSettings.get(FUNCTIONS_EXTENSION_VERSION));
        if (modified) {
            final Update update = remote.update();
            Optional.ofNullable(newPlan).filter(ignore -> planModified).ifPresent(p -> updateAppServicePlan(update, p));
            Optional.ofNullable(newRuntime).filter(ignore -> runtimeModified).ifPresent(p -> updateRuntime(update, p, funcExtVersion));
            Optional.of(settingsToAdd).filter(ignore -> isAppSettingsModified).ifPresent(update::withAppSettings);
            Optional.of(settingsToRemove).filter(CollectionUtils::isNotEmpty).filter(ignore -> isAppSettingsModified).ifPresent(s -> s.forEach(update::withoutAppSetting));
            Optional.ofNullable(newDockerConfig).filter(ignore -> dockerModified).ifPresent(p -> updateDockerConfiguration(update, p));
            Optional.ofNullable(newDiagnosticConfig).filter(ignore -> isDiagnosticConfigModified).filter(ignore -> StringUtils.isBlank(getEnvironmentId())).ifPresent(c -> AppServiceUtils.updateDiagnosticConfigurationForWebAppBase(update, c));
            Optional.ofNullable(newFlexConsumptionConfiguration).filter(ignore -> flexConsumptionModified).ifPresent(c -> update.withContainerSize(c.getInstanceSize()));
            Optional.ofNullable(storageAccount).ifPresent(s -> update.withExistingStorageAccount(s.getRemote()));
            final IAzureMessager messager = AzureMessager.getMessager();
            messager.info(AzureString.format("Start updating Function App({0})...", remote.name()));
            remote = update.apply();
            if (flexConsumptionModified) {
                updateFlexConsumptionConfiguration(remote, Objects.requireNonNull(newFlexConsumptionConfiguration));
            }
            messager.success(AzureString.format("Function App({0}) is successfully updated", remote.name()));
        }
        return remote;
    }

    private void updateAppServicePlan(@Nonnull Update update, @Nonnull AppServicePlan newPlan) {
        Objects.requireNonNull(newPlan.getRemote(), "Target app service plan doesn't exist");
        final OperatingSystem os = Objects.requireNonNull(getRuntime()).isDocker() ? OperatingSystem.LINUX : getRuntime().getOperatingSystem();
        if (os != newPlan.getOperatingSystem()) {
            throw new AzureToolkitRuntimeException(String.format("Could not migrate %s app service to %s service plan", getRuntime().getOperatingSystem(), newPlan.getOperatingSystem()));
        }
        update.withExistingAppServicePlan(newPlan.getRemote());
    }

    private void updateRuntime(@Nonnull Update update, @Nonnull Runtime newRuntime, String funcExtVersion) {
        final Runtime oldRuntime = Objects.requireNonNull(Objects.requireNonNull(origin).getRuntime());
        if (newRuntime.getOperatingSystem() != null && oldRuntime.getOperatingSystem() != newRuntime.getOperatingSystem()) {
            throw new AzureToolkitRuntimeException(CAN_NOT_UPDATE_EXISTING_APP_SERVICE_OS);
        }
        final OperatingSystem operatingSystem = ObjectUtils.firstNonNull(newRuntime.getOperatingSystem(), oldRuntime.getOperatingSystem());
        if (operatingSystem == OperatingSystem.LINUX) {
            update.withBuiltInImage(((FunctionAppLinuxRuntime) newRuntime).toFunctionRuntimeStack(funcExtVersion));
        } else if (operatingSystem == OperatingSystem.WINDOWS) {
            update.withJavaVersion(newRuntime.getJavaVersion()).withWebContainer(null);
        } else if (newRuntime.getOperatingSystem() == OperatingSystem.DOCKER) {
            return; // skip for docker, as docker configuration will be handled in `updateDockerConfiguration`
        } else {
            throw new AzureToolkitRuntimeException(String.format(UNSUPPORTED_OPERATING_SYSTEM, newRuntime.getOperatingSystem()));
        }
    }

    private void updateDockerConfiguration(@Nonnull Update update, @Nonnull DockerConfiguration newConfig) {
        if (StringUtils.isAllEmpty(newConfig.getUserName(), newConfig.getPassword())) {
            update.withPublicDockerHubImage(newConfig.getImage());
        } else if (StringUtils.isEmpty(newConfig.getRegistryUrl())) {
            update.withPrivateDockerHubImage(newConfig.getImage())
                .withCredentials(newConfig.getUserName(), newConfig.getPassword());
        } else {
            update.withPrivateRegistryImage(newConfig.getImage(), newConfig.getRegistryUrl())
                .withCredentials(newConfig.getUserName(), newConfig.getPassword());
        }
    }

    public void setRuntime(FunctionAppRuntime runtime) {
        this.ensureConfig().setRuntime(runtime);
    }

    @Nullable
    @Override
    public FunctionAppRuntime getRuntime() {
        return Optional.ofNullable(config).map(Config::getRuntime).orElseGet(super::getRuntime);
    }

    public void setStorageAccount(StorageAccount account) {
        this.ensureConfig().setStorageAccount(account);
    }

    public StorageAccount getStorageAccount() {
        return Optional.ofNullable(config).map(Config::getStorageAccount).orElse(null);
    }

    public void setAppServicePlan(AppServicePlan plan) {
        this.ensureConfig().setPlan(plan);
    }

    @Nullable
    @Override
    public AppServicePlan getAppServicePlan() {
        return Optional.ofNullable(config).map(Config::getPlan).orElseGet(super::getAppServicePlan);
    }

    public void setDiagnosticConfig(DiagnosticConfig config) {
        this.ensureConfig().setDiagnosticConfig(config);
    }

    @Nullable
    public DiagnosticConfig getDiagnosticConfig() {
        return Optional.ofNullable(config).map(Config::getDiagnosticConfig).orElseGet(super::getDiagnosticConfig);
    }

    public void setAppSettings(Map<String, String> appSettings) {
        this.ensureConfig().setAppSettings(appSettings);
    }

    public void setAppSetting(String key, String value) {
        this.ensureConfig().getAppSettings().put(key, value);
    }

    @Nullable
    @Override
    public Map<String, String> getAppSettings() {
        return Optional.ofNullable(config).map(Config::getAppSettings).orElseGet(super::getAppSettings);
    }

    public void removeAppSetting(String key) {
        this.ensureConfig().getAppSettingsToRemove().add(key);
    }

    public void removeAppSettings(Set<String> keys) {
        this.ensureConfig().getAppSettingsToRemove().addAll(ObjectUtils.firstNonNull(keys, Collections.emptySet()));
    }

    @Nullable
    public Set<String> getAppSettingsToRemove() {
        return Optional.ofNullable(config).map(Config::getAppSettingsToRemove).orElse(new HashSet<>());
    }

    public void setDockerConfiguration(DockerConfiguration dockerConfiguration) {
        this.ensureConfig().setDockerConfiguration(dockerConfiguration);
    }

    @Nullable
    public DockerConfiguration getDockerConfiguration() {
        return Optional.ofNullable(config).map(Config::getDockerConfiguration).orElse(null);
    }

    public void setFlexConsumptionConfiguration(FlexConsumptionConfiguration flexConsumptionConfiguration) {
        this.ensureConfig().setFlexConsumptionConfiguration(flexConsumptionConfiguration);
    }

    public void setEnableDistributedTracing(@Nullable final Boolean enableDistributedTracing) {
        this.ensureConfig().setEnableDistributedTracing(enableDistributedTracing);
    }

    @Nullable
    public FlexConsumptionConfiguration getFlexConsumptionConfiguration() {
        return Optional.ofNullable(config).map(Config::getFlexConsumptionConfiguration).orElseGet(super::getFlexConsumptionConfiguration);
    }

    public void setEnvironment(ContainerAppsEnvironment environment) {
        this.ensureConfig().setEnvironment(environment);
    }

    public void setContainerConfiguration(ContainerAppFunctionConfiguration containerConfiguration) {
        this.ensureConfig().setContainerConfiguration(containerConfiguration);
    }

    public ContainerAppsEnvironment getEnvironment() {
        return Optional.ofNullable(config).map(Config::getEnvironment).orElseGet(super::getEnvironment);
    }

    public String getEnvironmentId() {
        return Optional.ofNullable(config).map(Config::getEnvironment).map(ContainerAppsEnvironment::getId).orElseGet(super::getEnvironmentId);
    }

    public ContainerAppFunctionConfiguration getContainerConfiguration() {
        return Optional.ofNullable(config).map(Config::getContainerConfiguration).orElseGet(super::getContainerConfiguration);
    }

    @Override
    public boolean isModified() {
        final boolean notModified = Objects.isNull(this.config) ||
            Objects.isNull(this.config.getRuntime()) || Objects.equals(this.config.getRuntime(), super.getRuntime()) ||
            Objects.isNull(this.config.getPlan()) || Objects.equals(this.config.getPlan(), super.getAppServicePlan()) ||
            Objects.isNull(this.config.getDiagnosticConfig()) ||
            CollectionUtils.isEmpty(this.config.getAppSettingsToRemove()) ||
            Objects.isNull(this.config.getAppSettings()) || Objects.equals(this.config.getAppSettings(), super.getAppSettings()) ||
            Objects.isNull(this.config.getDockerConfiguration());
        return !notModified;
    }

    public void setRegion(Region region) {
        this.ensureConfig().setRegion(region);
    }

    public Region getRegion() {
        return Optional.ofNullable(config).map(Config::getRegion).orElseGet(super::getRegion);
    }

    /**
     * {@code null} means not modified for properties
     */
    @Data
    @Nullable
    private static class Config {
        private FunctionAppRuntime runtime;
        private Region region;
        private AppServicePlan plan = null;
        private StorageAccount storageAccount = null;
        private ContainerAppsEnvironment environment = null;
        private ContainerAppFunctionConfiguration containerConfiguration = null;
        private Boolean enableDistributedTracing = null;
        private DiagnosticConfig diagnosticConfig = null;
        private Set<String> appSettingsToRemove = new HashSet<>();
        private Map<String, String> appSettings = new HashMap<>();
        private DockerConfiguration dockerConfiguration = null;
        private FlexConsumptionConfiguration flexConsumptionConfiguration;
    }

    private static void updateFlexConsumptionConfiguration(final com.azure.resourcemanager.appservice.models.FunctionApp app, final FlexConsumptionConfiguration flexConfiguration) {
        final WebAppsClient webApps = app.manager().serviceClient().getWebApps();
        if (ObjectUtils.anyNotNull(flexConfiguration.getMaximumInstances(), flexConfiguration.getAlwaysReadyInstances())) {
            final SiteConfigResourceInner configuration = webApps.getConfiguration(app.resourceGroupName(), app.name());
            if (!Objects.equals(flexConfiguration.getMaximumInstances(), configuration.functionAppScaleLimit()) ||
                !Objects.equals(flexConfiguration.getAlwaysReadyInstances(), configuration.minimumElasticInstanceCount())) {
                configuration.withFunctionAppScaleLimit(flexConfiguration.getMaximumInstances())
                    .withMinimumElasticInstanceCount(flexConfiguration.getAlwaysReadyInstances());
                webApps.updateConfiguration(app.resourceGroupName(), app.name(), configuration);
            }
        }
    }
}