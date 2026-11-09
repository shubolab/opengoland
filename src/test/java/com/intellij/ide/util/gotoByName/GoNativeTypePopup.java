package com.intellij.ide.util.gotoByName;

import com.intellij.openapi.application.ModalityState;
import com.intellij.util.Consumer;
import java.util.Set;

/** Pinned-SDK test bridge: preserve the production chooser task, policy and cancellation. */
public final class GoNativeTypePopup {
  private GoNativeTypePopup() {}

  public static void schedule(ChooseByNamePopup popup, String text, boolean everywhere, Consumer<? super Set<?>> callback) {
    // The JVM API is public; its exact Kotlin selection policy is source-internal.
    popup.scheduleCalcElements(text, everywhere, ModalityState.nonModal(), SelectMostRelevant.INSTANCE, callback);
  }

  public static void cancelAndClose(ChooseByNamePopup popup) {
    // Same-package access is confined to tests. Cancel only this popup's task;
    // closing it also prevents the SDK's cancelled-read-task retry from restarting.
    popup.cancelListUpdater();
    popup.close(false);
  }
}
