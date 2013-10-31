package com.hubspot.dropwizard.guice;

import com.codahale.dropwizard.Configuration;
import com.codahale.dropwizard.ConfiguredBundle;
import com.codahale.dropwizard.setup.Bootstrap;
import com.codahale.dropwizard.setup.Environment;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.servlet.GuiceFilter;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;

public class GuiceBundle<T extends Configuration> implements ConfiguredBundle<T> {

    final Logger logger = LoggerFactory.getLogger(GuiceBundle.class);

    private final AutoConfig autoConfig;
    private final List<Module> modules;
    private final InjectorFactory injectorFactory;
    private Injector injector;
    private DropwizardEnvironmentModule dropwizardEnvironmentModule;
    private Optional<Class<T>> configurationClass;
    private GuiceContainer container;
    private Stage stage;
    

    /**
     * Factory to create Guice Injector with supplied Stage and List of Modules. 
     * 
     * Idea behind separating this out is to enable integrating applications to 
     * use alternate Guice factories like,
     * @see <a href=https://code.google.com/p/mycila/">Mycila</a> or 
     * @see <a href="https://github.com/Netflix/governator">Governator</a> etc.
     */
	public static interface InjectorFactory {
		public Injector create(final Stage stage, final List<Module> modules);
		
	}

    public static class Builder<T extends Configuration> {
        private AutoConfig autoConfig;
        private List<Module> modules = Lists.newArrayList();
        private Optional<Class<T>> configurationClass = Optional.<Class<T>>absent();
        
        private InjectorFactory injectorFactory = new InjectorFactory() {
			@Override
			public Injector create(final Stage stage, final List<Module> modules) {
				// Default
				return Guice.createInjector(stage, modules);
			}
		};

        public Builder<T> addModule(Module module) {
            Preconditions.checkNotNull(module);
            modules.add(module);
            return this;
        }

        public Builder<T> setConfigClass(Class<T> clazz) {
            configurationClass = Optional.of(clazz);
            return this;
        }
        
        public Builder<T> setInjectorFactory(InjectorFactory factory) {
        	Preconditions.checkNotNull(factory);
        	injectorFactory = factory;
        	return this;
        }

        public Builder<T> enableAutoConfig(String... basePackages) {
            Preconditions.checkNotNull(basePackages.length > 0);
            Preconditions.checkArgument(autoConfig == null, "autoConfig already enabled!");
            autoConfig = new AutoConfig(basePackages);
            return this;
        }

        public GuiceBundle<T> build() {
            return build(Stage.PRODUCTION);
        }

        public GuiceBundle<T> build(Stage s) {
            return new GuiceBundle<T>(s, autoConfig, modules, configurationClass, injectorFactory);
        }

    }
    
    public static <T extends Configuration> Builder<T> newBuilder() {
        return new Builder<T>();
    }

    private GuiceBundle(Stage stage, AutoConfig autoConfig, List<Module> modules, Optional<Class<T>> configurationClass, InjectorFactory injectorFactory) {
        Preconditions.checkNotNull(modules);
        Preconditions.checkArgument(!modules.isEmpty());
        this.modules = modules;
        this.autoConfig = autoConfig;
        this.configurationClass = configurationClass;
        this.injectorFactory = injectorFactory;
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        container = new GuiceContainer();
        JerseyContainerModule jerseyContainerModule = new JerseyContainerModule(container);
        if (configurationClass.isPresent()) {
            dropwizardEnvironmentModule = new DropwizardEnvironmentModule<T>(configurationClass.get());
        } else {
            dropwizardEnvironmentModule = new DropwizardEnvironmentModule<Configuration>(Configuration.class);
        }
        modules.add(jerseyContainerModule);
        modules.add(dropwizardEnvironmentModule);

        initInjector();

        if (autoConfig != null) {
            autoConfig.initialize(bootstrap, injector);
        }
    }

    private void initInjector() {
    	injector = injectorFactory.create(this.stage, ImmutableList.copyOf(this.modules));
    }

    @Override
    public void run(final T configuration, final Environment environment) {
        container.setResourceConfig(environment.jersey().getResourceConfig());
        environment.jersey().replace(new Function<ResourceConfig, ServletContainer>() {
            @Nullable
            @Override
            public ServletContainer apply(ResourceConfig resourceConfig) {
                return container;
            }
        });
        environment.servlets().addFilter("Guice Filter", GuiceFilter.class)
                .addMappingForUrlPatterns(null, false, environment.getApplicationContext().getContextPath() + "*");
        setEnvironment(configuration, environment);

        if (autoConfig != null) {
            autoConfig.run(environment, injector);
        }
    }

    @SuppressWarnings("unchecked")
    private void setEnvironment(final T configuration, final Environment environment) {
        dropwizardEnvironmentModule.setEnvironmentData(configuration, environment);
    }

    public Injector getInjector() {
        return injector;
    }
}
