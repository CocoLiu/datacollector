/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class BlackListURLClassLoader extends URLClassLoader {
  private final String type;
  private final String name;
  private final String[] blacklistedPackages;
  private final String[] blacklistedDirs;


  public BlackListURLClassLoader(String type, String name, List<URL> urls, ClassLoader parent,
                                 String[] blacklistedPackages) {
    super(urls.toArray(new URL[urls.size()]), parent);
    this.type = type;
    this.name = name;
    this.blacklistedPackages = (blacklistedPackages != null) ? blacklistedPackages : new String[0];
    blacklistedDirs = new String[this.blacklistedPackages.length];
    for (int i = 0; i < blacklistedDirs.length; i++) {
      blacklistedDirs[i] = this.blacklistedPackages[i].replace(".", "/");
    }
  }

  // Visible for testing only
  void validateClass(String name) {
    // python scripting engine generates __*__ classes under all packages (including SDC api package)
    if (!(name.endsWith("__"))) {
      for (String blacklistedPackage : blacklistedPackages) {
        if (name.startsWith(blacklistedPackage)) {
          throw new IllegalArgumentException(String.format("Class '%s' cannot be present in %s",
            name, toString()));
        }
      }
    }
  }

  // Visible for testing only
  void validateResource(String name) {
    for (String blacklistedPackage : blacklistedDirs) {
      if (name.startsWith(blacklistedPackage)) {
        throw new IllegalArgumentException(String.format("Resource '%s' cannot be present in %s",
                                                         name, toString()));
      }
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    validateClass(name);
    return super.findClass(name);
  }

  @Override
  public URL findResource(String name) {
    validateResource(name);
    return super.findResource(name);
  }

  @Override
  public Enumeration<URL> findResources(String name) throws IOException {
    validateResource(name);
    return super.findResources(name);
  }

  public String toString() {
    return String.format("BlackListURLClassLoader[type=%s name=%s]", type, name);
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public String dumpClasspath(String linePrefix) {
    List<String> entries = new ArrayList<>();
    dumpClasspath(this, entries);
    StringBuilder sb = new StringBuilder();
    sb.append(linePrefix).append("ClassLoader name=").append(getName()).append(" type=").append(getType()).append("\n");
    for (String entry : entries) {
      sb.append(linePrefix).append("  ").append(entry).append("\n");
    }
    return sb.toString();
  }

  private String dumpClasspath(ClassLoader classLoader, List<String> entries) {
    String prefix = "";
    if (classLoader.getParent() != null && classLoader.getParent() instanceof URLClassLoader) {
      prefix += "  " + dumpClasspath(classLoader.getParent(), entries);
    }
    if (classLoader instanceof URLClassLoader) {
      for (URL url : ((URLClassLoader) classLoader).getURLs()) {
        entries.add(prefix + url.toString());
      }
    }
    return prefix;
  }

}
