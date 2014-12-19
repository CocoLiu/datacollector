/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.api.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LocalizableMessage implements LocalizableString {
  private static final Logger LOG = LoggerFactory.getLogger(LocalizableMessage.class);
  private static final Object[] NULL_ONE_ARG = {null};

  private static final Set<String> MISSING_BUNDLE_WARNS =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
  private static final Set<String> MISSING_KEY_WARNS =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  private final ClassLoader classLoader;
  private final String bundle;
  private final String id;
  private final String template;
  private final Object[] args;

  public LocalizableMessage(ClassLoader classLoader, String bundle, String id, String defaultTemplate, Object[] args) {
    this.classLoader = Utils.checkNotNull(classLoader, "classLoader");
    this.bundle = bundle;
    this.id = id;
    this.template = defaultTemplate;
    // we need to do this trick because of the ... syntax sugar resolution when a single value 'null' is used
    this.args = (args != null) ? args : NULL_ONE_ARG;
  }

  public LocalizableMessage(String bundle, String id, String defaultTemplate, Object[] args) {
    this(Thread.currentThread().getContextClassLoader(), bundle, id, defaultTemplate, args);
  }

  @Override
  public String getNonLocalized() {
    return Utils.format(template, args);
  }

  @Override
  public String getLocalized() {
    String templateToUse = template;
    if (bundle != null) {
      Locale locale = LocaleInContext.get();
      if (locale != null) {
        try {
          ResourceBundle rb = ResourceBundle.getBundle(bundle, locale, classLoader);
          if (rb.containsKey(id)) {
            templateToUse = rb.getString(id);
          } else {
            if (!MISSING_KEY_WARNS.contains(bundle + " " + id)) {
              MISSING_KEY_WARNS.add(bundle + " " + id);
              LOG.warn("ResourceBundle '{}' does not have key '{}' via ClassLoader '{}'", bundle, id, classLoader);
            }
          }
        } catch (MissingResourceException ex) {
          if (!MISSING_BUNDLE_WARNS.contains(bundle)) {
            MISSING_BUNDLE_WARNS.add(bundle);
            LOG.warn("ResourceBundle '{}' not found via ClassLoader '{}'", bundle, classLoader);
          }
        }
      }
    }
    return Utils.format(templateToUse, args);
  }

  public String toString() {
    return getNonLocalized();
  }

}