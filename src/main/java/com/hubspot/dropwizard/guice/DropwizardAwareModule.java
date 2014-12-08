package com.hubspot.dropwizard.guice;

import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;

public interface DropwizardAwareModule<T extends Configuration> {

    public void setEnvironmentData(T configuration, Environment environment);

}
