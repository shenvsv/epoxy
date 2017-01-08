package com.airbnb.epoxy;

import android.support.annotation.LayoutRes;
import android.view.View;
import android.view.ViewGroup;

public abstract class EpoxyModelWithView<T extends View> extends EpoxyModel<T> {

  @Override
  protected final int getDefaultLayout() {
    return 0;
  }

  @Override
  public EpoxyModel<T> layout(@LayoutRes int layoutRes) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected abstract int getViewType();

  @Override
  protected abstract T buildView(ViewGroup parent);
}
