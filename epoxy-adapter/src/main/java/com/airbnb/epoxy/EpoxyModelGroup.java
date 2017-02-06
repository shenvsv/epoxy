package com.airbnb.epoxy;

import android.support.annotation.LayoutRes;
import android.view.View;
import android.view.ViewStub;

import com.airbnb.epoxy.EpoxyModelGroup.Holder;
import com.airbnb.viewmodeladapter.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Allows you to combine multiple models in whatever view configuration you want. */
@SuppressWarnings("rawtypes")
public class EpoxyModelGroup extends EpoxyModelWithHolder<Holder> {
  /**
   * We have a hard cap on number of models since we have hardcoded ids for clarity and simplicity.
   */
  private static final int MAX_MODELS_SUPPORTED = 5;

  protected final List<EpoxyModel> models;
  /** By default we save view state if any of the models need to save state. */
  private final boolean shouldSaveViewState;

  /**
   * @param layoutRes The layout to use with these models. See {@link #layout(int)} for how this
   *                  should be structured.
   * @param models    The models that will be used to bind the views in the given layout.
   */
  public EpoxyModelGroup(@LayoutRes int layoutRes, Collection<EpoxyModel> models) {
    this(layoutRes, new ArrayList<>(models));
  }

  /**
   * @param layoutRes The layout to use with these models. See {@link #layout(int)} for how this
   *                  should be structured.
   * @param models    The models that will be used to bind the views in the given layout.
   */
  public EpoxyModelGroup(@LayoutRes int layoutRes, EpoxyModel... models) {
    this(layoutRes, new ArrayList<>(Arrays.asList(models)));
  }

  /**
   * @param layoutRes The layout to use with these models. See {@link #layout(int)} for how this
   *                  should be structured.
   * @param models    The models that will be used to bind the views in the given layout.
   */
  private EpoxyModelGroup(@LayoutRes int layoutRes, List<EpoxyModel> models) {
    if (models.isEmpty()) {
      throw new IllegalArgumentException("Models cannot be empty");
    }

    if (models.size() > MAX_MODELS_SUPPORTED) {
      throw new IllegalArgumentException(
          "Too many models. Only " + MAX_MODELS_SUPPORTED + " models are supported");
    }

    this.models = models;
    layout(layoutRes);
    id(models.get(0).id());

    boolean saveState = false;
    for (EpoxyModel<?> model : models) {
      if (model.shouldSaveViewState()) {
        saveState = true;
        break;
      }
    }

    shouldSaveViewState = saveState;
  }

  /**
   * The layout provided by this must have a separate {@link ViewStub} child for each model. That
   * model's layout will be inflated into the corresponding view stub. Each view stub should have an
   * id indicating which model it belongs to, defined by the {@link Holder#getIdForIndex(int)}
   * method.
   */
  @Override
  public EpoxyModelGroup layout(@LayoutRes int layoutRes) {
    super.layout(layoutRes);
    return this;
  }

  @Override
  public final void bind(Holder holder) {
    iterateModels(holder, new IterateModelsCallback() {
      @Override
      public void onModel(EpoxyModel model, Object boundObject, View view) {
        setViewVisibility(model, view);
        if (model.isShown()) {
          //noinspection unchecked
          model.bind(boundObject);
        }
      }
    });
  }

  @Override
  public final void bind(Holder holder, final List<Object> payloads) {
    iterateModels(holder, new IterateModelsCallback() {
      @Override
      public void onModel(EpoxyModel model, Object boundObject, View view) {
        setViewVisibility(model, view);
        if (model.isShown()) {
          //noinspection unchecked
          model.bind(boundObject, payloads);
        }
      }
    });
  }

  private static void setViewVisibility(EpoxyModel model, View view) {
    if (model.isShown()) {
      view.setVisibility(View.VISIBLE);
    } else {
      view.setVisibility(View.GONE);
    }
  }

  @Override
  public final void unbind(Holder holder) {
    iterateModels(holder, new IterateModelsCallback() {
      @Override
      public void onModel(EpoxyModel model, Object boundObject, View view) {
        //noinspection unchecked
        model.unbind(boundObject);
      }
    });
  }

  @Override
  public void onViewAttachedToWindow(Holder holder) {
    iterateModels(holder, new IterateModelsCallback() {
      @Override
      public void onModel(EpoxyModel model, Object boundObject, View view) {
        //noinspection unchecked
        model.onViewAttachedToWindow(boundObject);
      }
    });
  }

  @Override
  public void onViewDetachedFromWindow(Holder holder) {
    iterateModels(holder, new IterateModelsCallback() {
      @Override
      public void onModel(EpoxyModel model, Object boundObject, View view) {
        //noinspection unchecked
        model.onViewDetachedFromWindow(boundObject);
      }
    });
  }

  private void iterateModels(Holder holder, IterateModelsCallback callback) {
    int modelCount = models.size();
    for (int i = 0; i < modelCount; i++) {
      EpoxyModel model = models.get(i);
      View view = holder.views.get(i);
      EpoxyHolder epoxyHolder = holder.holders.get(i);
      Object objectToBind = (model instanceof EpoxyModelWithHolder) ? epoxyHolder : view;

      callback.onModel(model, objectToBind, view);
    }
  }

  private interface IterateModelsCallback {
    void onModel(EpoxyModel model, Object boundObject, View view);
  }

  @Override
  public int getSpanSize(int totalSpanCount, int position, int itemCount) {
    // Defaults to using the span size of the first model. Override this if you need to customize it
    return models.get(0).getSpanSize(totalSpanCount, position, itemCount);
  }

  @Override
  protected final int getDefaultLayout() {
    throw new UnsupportedOperationException(
        "You should set a layout with layout(...) instead of using this.");
  }

  @Override
  public boolean shouldSaveViewState() {
    // By default state is saved if any of the models have saved state enabled.
    // Override this if you need custom behavior.
    return shouldSaveViewState;
  }

  @Override
  protected final Holder createNewHolder() {
    return new Holder();
  }

  protected class Holder extends EpoxyHolder {
    private List<View> views;
    private List<EpoxyHolder> holders;

    @Override
    protected void bindView(View itemView) {
      int modelCount = models.size();
      views = new ArrayList<>(modelCount);
      holders = new ArrayList<>(modelCount);

      for (int i = 0; i < modelCount; i++) {
        EpoxyModel<?> model = models.get(i);
        ViewStub viewStub = getViewStub(itemView, i, model);
        viewStub.setLayoutResource(model.getLayout());
        View view = viewStub.inflate();

        if (model instanceof EpoxyModelWithHolder) {
          EpoxyHolder holder = ((EpoxyModelWithHolder) model).createNewHolder();
          holder.bindView(view);
          holders.add(holder);
        } else {
          holders.add(null);
        }

        views.add(view);
      }
    }

    private ViewStub getViewStub(View itemView, int i, EpoxyModel<?> model) {
      View stub = itemView.findViewById(getIdForIndex(i));

      if (stub == null) {
        throw new IllegalStateException(
            "The expected view for your model " + model + " at position " + i +
                " wasn't found. Are you using the correct stub id?");
      }

      if (stub instanceof ViewStub) {
        return (ViewStub) stub;
      }

      throw new IllegalStateException(
          "Your layout should provide a ViewStub. See the layout() method javadoc for more info.");
    }

    private int getIdForIndex(int modelIndex) {
      switch (modelIndex) {
        case 0:
          return R.id.model_mixer_view_stub_1;
        case 1:
          return R.id.model_mixer_view_stub_2;
        case 2:
          return R.id.model_mixer_view_stub_3;
        case 3:
          return R.id.model_mixer_view_stub_4;
        case 4:
          return R.id.model_mixer_view_stub_5;
        default:
          throw new IllegalStateException(
              "No support for more than " + MAX_MODELS_SUPPORTED + " models");
      }
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EpoxyModelGroup)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    EpoxyModelGroup that = (EpoxyModelGroup) o;

    return models.equals(that.models);
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + models.hashCode();
    return result;
  }
}