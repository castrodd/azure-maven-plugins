/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.action;

import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.AzResourceModule;
import com.microsoft.azure.toolkit.lib.common.operation.Operation;
import com.microsoft.azure.toolkit.lib.common.operation.OperationBase;
import com.microsoft.azure.toolkit.lib.common.operation.OperationBundle;
import com.microsoft.azure.toolkit.lib.common.operation.OperationContext;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import com.microsoft.azure.toolkit.lib.common.view.IView;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.PropertyKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

@SuppressWarnings("unused")
@Accessors(chain = true)
public class Action<D> extends OperationBase {
    public static final String SOURCE = "ACTION_SOURCE";
    public static final String RESOURCE_TYPE = "resourceType";
    public static final Id<Runnable> REQUIRE_AUTH = Id.of("common.requireAuth");
    public static final Id<Object> AUTHENTICATE = Id.of("account.authenticate");
    @Nonnull
    private final List<AbstractMap.SimpleEntry<BiPredicate<D, ?>, BiConsumer<D, ?>>> handlers = new ArrayList<>();
    @Nonnull
    private final Id<D> id;
    @Nonnull
    private Predicate<Object> enableWhen = o -> true;
    private Function<D, String> iconProvider;
    private Function<D, String> labelProvider;
    private Function<D, AzureString> titleProvider;
    @Nonnull
    private final List<Function<D, String>> titleParamProviders = new ArrayList<>();

    @Setter
    @Getter
    private boolean authRequired = true;
    /**
     * shortcuts for this action.
     * 1. directly bound to this action if it's IDE-specific type of shortcuts (e.g. {@code ShortcutSet} in IntelliJ).
     * 2. interpreted into native shortcuts first and then bound to this action if it's {@code String[]/String} (e.g. {@code "alt X"}).
     * 3. copy shortcuts from actions specified by this action id and then bound to this action if it's {@link Id} of another action.
     */
    @Setter
    @Getter
    private Object shortcut;

    public Action(@Nonnull Id<D> id) {
        this.id = id;
    }

    @Nonnull
    @Override
    public String getId() {
        return this.id.id;
    }

    @Nullable
    public IView.Label getView(D s) {
        try {
            if (this.enableWhen.test(s)) {
                final String i = Optional.ofNullable(this.iconProvider).map(p -> p.apply(s)).orElse(null);
                final AzureString t = Optional.ofNullable(this.titleProvider).map(p -> p.apply(s)).orElse(null);
                return new View(this.labelProvider.apply(s), i, t, true);
            }
            return new View("", "", false);
        } catch (final Exception e) {
            e.printStackTrace();
            return new View("", "", false);
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public BiConsumer<D, Object> getHandler(D source, Object e) {
        if (!this.enableWhen.test(source)) {
            return null;
        }
        for (int i = this.handlers.size() - 1; i >= 0; i--) {
            final AbstractMap.SimpleEntry<BiPredicate<D, ?>, BiConsumer<D, ?>> p = this.handlers.get(i);
            final BiPredicate<D, Object> condition = (BiPredicate<D, Object>) p.getKey();
            final BiConsumer<D, Object> handler = (BiConsumer<D, Object>) p.getValue();
            if (condition.test(source, e)) {
                return handler;
            }
        }
        return null;
    }

    public void handle(D source, Object e) {
        final BiConsumer<D, Object> handler = this.getHandler(source, e);
        if (Objects.isNull(handler)) {
            return;
        }
        final AzureString title = this.getTitle(source);
        final Runnable operationBody = () -> AzureTaskManager.getInstance().runInBackground(title, () -> handle(source, e, handler));
        final Runnable handlerBody = () -> Operation.execute(title, Type.USER, operationBody, source);
        if (this.authRequired) {
            final Action<Runnable> requireAuth = AzureActionManager.getInstance().getAction(REQUIRE_AUTH);
            if (Objects.nonNull(requireAuth)) {
                requireAuth.handle(handlerBody, e);
            }
        } else {
            handlerBody.run();
        }
    }

    protected void handle(D source, Object e, BiConsumer<D, Object> handler) {
        if (source instanceof AzResource) {
            final AzResource resource = (AzResource) source;
            final OperationContext context = OperationContext.action();
            context.setTelemetryProperty("subscriptionId", resource.getSubscriptionId());
            context.setTelemetryProperty("resourceType", resource.getFullResourceType());
        } else if (source instanceof AzResourceModule) {
            final AzResourceModule<?> resource = (AzResourceModule<?>) source;
            final OperationContext context = OperationContext.action();
            context.setTelemetryProperty("subscriptionId", resource.getSubscriptionId());
            context.setTelemetryProperty("resourceType", resource.getFullResourceType());
        }
        handler.accept(source, e);
    }

    public void handle(D source) {
        this.handle(source, null);
    }

    @Override
    public Callable<?> getBody() {
        throw new AzureToolkitRuntimeException("'action.getBody()' is not supported");
    }

    @Nonnull
    @Override
    public String getType() {
        return Type.USER;
    }

    public AzureString getTitle(D source) {
        if (Objects.nonNull(this.titleProvider)) {
            return this.titleProvider.apply(source);
        } else if (!this.titleParamProviders.isEmpty()) {
            final Object[] params = this.titleParamProviders.stream().map(p -> p.apply(source)).toArray();
            return OperationBundle.description(this.id.id, params);
        }
        return this.getDescription();
    }

    @Nonnull
    @Override
    public AzureString getDescription() {
        return OperationBundle.description(this.id.id);
    }

    public Action<D> enableWhen(@Nonnull Predicate<Object> enableWhen) {
        this.enableWhen = enableWhen;
        return this;
    }

    public Action<D> withLabel(@Nonnull final String label) {
        this.labelProvider = (any) -> label;
        return this;
    }

    public Action<D> withLabel(@Nonnull final Function<D, String> labelProvider) {
        this.labelProvider = labelProvider;
        return this;
    }

    public Action<D> withIcon(@Nonnull final String icon) {
        this.iconProvider = (any) -> icon;
        return this;
    }

    public Action<D> withIcon(@Nonnull final Function<D, String> iconProvider) {
        this.iconProvider = iconProvider;
        return this;
    }

    public Action<D> withTitle(@Nonnull final AzureString title) {
        this.titleProvider = (any) -> title;
        return this;
    }

    public Action<D> withShortcut(@Nonnull final Object shortcut) {
        this.shortcut = shortcut;
        return this;
    }

    public Action<D> withHandler(@Nonnull Consumer<D> handler) {
        this.handlers.add(new AbstractMap.SimpleEntry<>((d, e) -> true, (d, e) -> handler.accept(d)));
        return this;
    }

    public <E> Action<D> withHandler(@Nonnull BiConsumer<D, E> handler) {
        this.handlers.add(new AbstractMap.SimpleEntry<>((d, e) -> true, handler));
        return this;
    }

    public Action<D> withHandler(@Nonnull Predicate<D> condition, @Nonnull Consumer<D> handler) {
        this.handlers.add(new AbstractMap.SimpleEntry<>((d, e) -> condition.test(d), (d, e) -> handler.accept(d)));
        return this;
    }

    public <E> Action<D> withHandler(@Nonnull BiPredicate<D, E> condition, @Nonnull BiConsumer<D, E> handler) {
        this.handlers.add(new AbstractMap.SimpleEntry<>(condition, handler));
        return this;
    }

    public Action<D> withAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
        return this;
    }

    public Action<D> withIdParam(@Nonnull final String titleParam) {
        this.titleParamProviders.add((d) -> titleParam);
        return this;
    }

    public Action<D> withIdParam(@Nonnull final Function<D, String> titleParamProvider) {
        this.titleParamProviders.add(titleParamProvider);
        return this;
    }

    public void register(AzureActionManager am) {
        am.registerAction(this);
    }

    public static class Id<D> {
        @Nonnull
        private final String id;

        private Id(@Nonnull String id) {
            this.id = id;
        }

        public static <D> Id<D> of(@PropertyKey(resourceBundle = OperationBundle.BUNDLE) @Nonnull String id) {
            assert StringUtils.isNotBlank(id) : "action id can not be blank";
            return new Id<>(id);
        }

        @Nonnull
        public String getId() {
            return id;
        }
    }

    @Getter
    @RequiredArgsConstructor
    @AllArgsConstructor
    public static class View implements IView.Label {
        @Nonnull
        private final String label;
        private final String iconPath;
        @Nullable
        private AzureString title;
        private final boolean enabled;

        @Override
        public String getDescription() {
            return Optional.ofNullable(this.title).map(AzureString::toString).orElse(null);
        }

        @Override
        public void dispose() {
        }
    }

    public static Action<Void> retryFromFailure(@Nonnull Runnable handler) {
        return new Action<>(Id.<Void>of("common.retry"))
            .withHandler((v) -> handler.run())
            .withLabel("Retry");
    }
}

