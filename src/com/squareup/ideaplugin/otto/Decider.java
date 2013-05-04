package com.squareup.ideaplugin.otto;

import com.intellij.usages.Usage;

public interface Decider {
  boolean shouldShow(Usage usage);
}
