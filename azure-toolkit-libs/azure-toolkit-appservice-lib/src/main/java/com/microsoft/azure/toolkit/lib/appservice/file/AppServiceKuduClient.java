/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.file;

import com.azure.core.annotation.BodyParam;
import com.azure.core.annotation.Delete;
import com.azure.core.annotation.Get;
import com.azure.core.annotation.HeaderParam;
import com.azure.core.annotation.Headers;
import com.azure.core.annotation.Host;
import com.azure.core.annotation.HostParam;
import com.azure.core.annotation.PathParam;
import com.azure.core.annotation.Post;
import com.azure.core.annotation.Put;
import com.azure.core.annotation.ServiceInterface;
import com.azure.core.http.rest.Response;
import com.azure.core.http.rest.RestProxy;
import com.azure.core.http.rest.StreamResponse;
import com.azure.core.util.FluxUtil;
import com.azure.resourcemanager.appservice.models.WebAppBase;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.appservice.AppServiceAppBase;
import com.microsoft.azure.toolkit.lib.appservice.model.AppServiceFile;
import com.microsoft.azure.toolkit.lib.appservice.model.CommandOutput;
import com.microsoft.azure.toolkit.lib.appservice.model.ProcessInfo;
import com.microsoft.azure.toolkit.lib.appservice.model.TunnelStatus;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.utils.JsonUtils;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class AppServiceKuduClient implements IFileClient, IProcessClient {
    public static final String DEFAULT_TOOL_NAME = "Azure-Java-Toolkit";
    private final String host;
    private final KuduService kuduService;
    private final AppServiceAppBase<?, ?, ?> app;
    private static final String HOME_PREFIX = "/home";

    private AppServiceKuduClient(String host, KuduService kuduService, AppServiceAppBase<?, ?, ?> app) {
        this.host = host;
        this.app = app;
        this.kuduService = kuduService;
    }

    public static AppServiceKuduClient getClient(@Nonnull WebAppBase webAppBase, @Nonnull AppServiceAppBase<?, ?, ?> appService) {
        // refers : https://github.com/Azure/azure-sdk-for-java/blob/master/sdk/resourcemanager/azure-resourcemanager-appservice/src/main/java/
        // com/azure/resourcemanager/appservice/implementation/KuduClient.java
        if (webAppBase.defaultHostname() == null) {
            throw new AzureToolkitRuntimeException("Cannot initialize kudu client before web app is created");
        }
        String host = webAppBase.defaultHostname().toLowerCase(Locale.ROOT)
            .replace("http://", "")
            .replace("https://", "");
        String[] parts = host.split("\\.", 2);
        host = parts[0] + ".scm." + parts[1];
        host = "https://" + host;

        final KuduService kuduService = RestProxy.create(KuduService.class, webAppBase.manager().httpPipeline());
        return new AppServiceKuduClient(host, kuduService, appService);
    }

    public Flux<ByteBuffer> getFileContent(final String path) {
        final String fixedPath = StringUtils.removeStart(path, HOME_PREFIX);
        return this.kuduService.getFileContent(host, fixedPath).flatMapMany(StreamResponse::getValue);
    }

    public List<? extends AppServiceFile> getFilesInDirectory(String dir) {
        // this file is generated by kudu itself, should not be visible to user.
        final String fixedDir = StringUtils.removeStart(dir, HOME_PREFIX);
        return Objects.requireNonNull(this.kuduService.getFilesInDirectory(host, fixedDir).block()).getValue().stream()
                .filter(file -> !"text/xml".equals(file.getMime()) || !file.getName().contains("LogFiles-kudu-trace_pending.xml"))
                .map(file -> file.withApp(app).withPath(Paths.get(fixedDir, file.getName()).toString()))
                .collect(Collectors.toList());
    }

    public AppServiceFile getFileByPath(String path) {
        final String fixedPath = StringUtils.removeStart(path, HOME_PREFIX);
        final File file = new File(fixedPath);
        final List<? extends AppServiceFile> result = getFilesInDirectory(file.getParent());
        return result.stream()
                .filter(appServiceFile -> StringUtils.equals(file.getName(), appServiceFile.getName()))
                .findFirst()
                .orElse(null);
    }

    public void uploadFileToPath(String content, String path) {
        this.kuduService.saveFile(host, path, content).block();
    }

    public void createDirectory(String path) {
        this.kuduService.createDirectory(host, path).block();
    }

    public void deleteFile(String path) {
        this.kuduService.deleteFile(host, path).block();
    }

    public List<ProcessInfo> listProcess() {
        return Objects.requireNonNull(this.kuduService.listProcess(host).block()).getValue();
    }

    public CommandOutput execute(final String command, final String dir) {
        final CommandRequest commandRequest = CommandRequest.builder().command(command).dir(dir).build();
        return Objects.requireNonNull(kuduService.execute(host, JsonUtils.toJson(commandRequest)).block()).getValue();
    }

    public void flexZipDeploy(final @Nonnull File zipFile) throws IOException {
        final AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(zipFile.toPath(), StandardOpenOption.READ);
        final Flux<ByteBuffer> byteBuffer = FluxUtil.readFile(fileChannel);
        final String product = Azure.az().config().getProduct();
        final String version = Azure.az().config().getVersion();
        final String tool = StringUtils.isAllBlank(product, version) ? DEFAULT_TOOL_NAME : String.format("%s/%s", product, version);
        kuduService.flexZipDeploy(host, byteBuffer,fileChannel.size(), tool).block();
    }

    public TunnelStatus getAppServiceTunnelStatus() {
        return Objects.requireNonNull(this.kuduService.getAppServiceTunnelStatus(host).block()).getValue();
    }

    @Host("{$host}")
    @ServiceInterface(name = "KuduService")
    private interface KuduService {
        @Headers({
                "Content-Type: application/json; charset=utf-8"
        })
        @Get("api/vfs/{path}")
        Mono<StreamResponse> getFileContent(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
                "Content-Type: application/json; charset=utf-8"
        })
        @Get("api/vfs/{path}/")
        Mono<Response<List<AppServiceFile>>> getFilesInDirectory(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
                "Content-Type: application/octet-stream; charset=utf-8",
                "If-Match: *"
        })
        @Put("api/vfs/{path}")
        Mono<Void> saveFile(@HostParam("$host") String host, @PathParam("path") String path, @BodyParam("application/octet-stream") String content);

        @Headers({
                "Content-Type: application/json; charset=utf-8"
        })
        @Put("api/vfs/{path}/")
        Mono<Void> createDirectory(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
                "Content-Type: application/json; charset=utf-8",
                "If-Match: *"
        })
        @Delete("api/vfs/{path}")
        Mono<Void> deleteFile(@HostParam("$host") String host, @PathParam("path") String path);

        @Headers({
                "x-ms-body-logging: false"
        })
        @Get("api/processes")
        Mono<Response<List<ProcessInfo>>> listProcess(@HostParam("$host") String host);

        @Headers({
                "Content-Type: application/json; charset=utf-8",
                "x-ms-body-logging: false"
        })
        @Post("api/command")
        Mono<Response<CommandOutput>> execute(@HostParam("$host") String host, @BodyParam("json") String command);

        @Headers({"Content-Type: application/zip"})
        @Post("api/deploy/zip?Deployer={tool}")
        Mono<Void> flexZipDeploy(@HostParam("$host") String host, @BodyParam("application/octet-stream") Flux<ByteBuffer> zipFile,
                                 @HeaderParam("content-length") long size, @PathParam("tool") String tool);

        @Headers({
                "Content-Type: application/json; charset=utf-8",
                "x-ms-body-logging: false"
        })
        @Get("AppServiceTunnel/Tunnel.ashx?GetStatus&GetStatusAPIVer=2")
        Mono<Response<TunnelStatus>> getAppServiceTunnelStatus(@HostParam("$host") String host);
    }

    @Data
    @SuperBuilder(toBuilder = true)
    public static class CommandRequest {
        private String command;
        private String dir;
    }
}