package io.github.suppierk.shoostr.extensions;

import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.RouteExtension;

interface TestExtension<C> extends ApplicationExtension<C>, RouteExtension<C> {}
