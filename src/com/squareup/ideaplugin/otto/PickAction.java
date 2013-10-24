package com.squareup.ideaplugin.otto;

import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

public class PickAction {

  public enum Type {
    PRODUCER("@Produce"),
    EVENT_POST("Post events");
    private String name;

    private Type(String name) {
      this.name = name;
    }

    @Override public String toString() {
      return name;
    }
  }

  public static void startPicker(Type[] displayedTypes, RelativePoint relativePoint,
                                 final Callback callback) {

    ListPopup listPopup = JBPopupFactory.getInstance()
        .createListPopup(new BaseListPopupStep<Type>("Select Type", displayedTypes) {
          @NotNull @Override public String getTextFor(Type value) {
            return value.toString();
          }

          @Override public PopupStep onChosen(Type selectedValue, boolean finalChoice) {
            callback.onTypeChose(selectedValue);
            return super.onChosen(selectedValue, finalChoice);
          }
        });

    listPopup.show(relativePoint);
  }

  public interface Callback {
    void onTypeChose(Type clazz);
  }
}
