/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho;

import static android.support.annotation.Dimension.DP;
import static com.facebook.litho.FrameworkLogEvents.EVENT_WARNING;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MESSAGE;

import android.animation.AnimatorInflater;
import android.animation.StateListAnimator;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.AttrRes;
import android.support.annotation.ColorInt;
import android.support.annotation.DimenRes;
import android.support.annotation.Dimension;
import android.support.annotation.DrawableRes;
import android.support.annotation.Px;
import android.support.annotation.StringRes;
import android.support.annotation.StyleRes;
import android.support.annotation.VisibleForTesting;
import android.util.SparseArray;
import android.view.ViewOutlineProvider;
import com.facebook.infer.annotation.ReturnsOwnership;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.infer.annotation.ThreadSafe;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.reference.DrawableReference;
import com.facebook.litho.reference.Reference;
import com.facebook.yoga.YogaAlign;
import com.facebook.yoga.YogaDirection;
import com.facebook.yoga.YogaEdge;
import com.facebook.yoga.YogaJustify;
import com.facebook.yoga.YogaPositionType;
import com.facebook.yoga.YogaWrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Represents a unique instance of a component. To create new {@link Component} instances, use the
 * {@code create()} method in the generated subclass which returns a builder that allows you to set
 * values for individual props. {@link Component} instances are immutable after creation.
 */
public abstract class Component extends ComponentLifecycle
    implements Cloneable, HasEventDispatcher, HasEventTrigger {

  private static final AtomicInteger sIdGenerator = new AtomicInteger(1);
  private int mId = sIdGenerator.getAndIncrement();
  private String mGlobalKey;
  @Nullable private String mKey;
  private boolean mHasManualKey;

  @ThreadConfined(ThreadConfined.ANY)
  private ComponentContext mScopedContext;

  private boolean mIsLayoutStarted = false;

  // If we have a cachedLayout, onPrepare and onMeasure would have been called on it already.
  @ThreadConfined(ThreadConfined.ANY)
  private InternalNode mLastMeasuredLayout;

  @Nullable private CommonPropsHolder mCommonPropsHolder;

  /**
   * Holds onto how many direct component children of each type this Component has. Used for
   * automatically generating unique global keys for all sibling components of the same type.
   */
  @Nullable private Map<String, Integer> mChildCounters;

  // Keep hold of the layout that we resolved during will render in order to use it again in
  // createLayout.
  @Nullable InternalNode mLayoutCreatedInWillRender;

  protected Component() {
    this(null);
  }

  /**
   * This constructor should be called only if working with a manually crafted "special" Component.
   * This should NOT be used in general use cases. Use the standard {@link #Component()} instead.
   */
  protected Component(Class classType) {
    super(classType);
    if (!ComponentsConfiguration.lazyInitializeComponent) {
      mChildCounters = new HashMap<>();
      mKey = Integer.toString(getTypeId());
    }
  }

  /**
   * Mostly used by logging to provide more readable messages.
   */
  public abstract String getSimpleName();

  /**
   * Compares this component to a different one to check if they are the same
   *
   * This is used to be able to skip rendering a component again. We avoid using the
   * {@link Object#equals(Object)} so we can optimize the code better over time since we don't have
   * to adhere to the contract required for a equals method.
   *
   * @param other the component to compare to
   * @return true if the components are of the same type and have the same props
   */
  public boolean isEquivalentTo(Component other) {
    return this == other;
  }

  protected StateContainer getStateContainer() {
    return null;
  }

  public ComponentContext getScopedContext() {
    return mScopedContext;
  }

  public void setScopedContext(ComponentContext scopedContext) {
    mScopedContext = scopedContext;
  }

  synchronized void markLayoutStarted() {
    if (mIsLayoutStarted) {
      throw new IllegalStateException("Duplicate layout of a component: " + this);
    }
    mIsLayoutStarted = true;
  }

  // Get an id that is identical across cloned instances, but otherwise unique
  protected int getId() {
    return mId;
  }

  /**
   * Get a key that is unique to this component within its tree.
   * @return
   */
  String getGlobalKey() {
    return mGlobalKey;
  }

  /**
   * Set a key for this component that is unique within its tree.
   * @param key
   *
   */
  // thread-safe because the one write is before all the reads
  @ThreadSafe(enableChecks = false)
  private void setGlobalKey(String key) {
    mGlobalKey = key;
  }

  /**
   *
   * @return a key that is local to the component's parent.
   */
  String getKey() {
    if (mKey == null && !mHasManualKey) {
      mKey = Integer.toString(getTypeId());
    }
    return mKey;
  }

  /**
   * Set a key that is local to the parent of this component.
   * @param key key
   */
  void setKey(String key) {
    mHasManualKey = true;
    mKey = key;
  }

  /**
   * Generate a global key for the given component that is unique among all of this component's
   * children of the same type. If a manual key has been set on the child component using the .key()
   * method, return the manual key.
   *
   * @param component the child component for which we're finding a unique global key
   * @param key the key of the child component as determined by its lifecycle id or manual setting
   * @return a unique global key for this component relative to its siblings.
   */
  private String generateUniqueGlobalKeyForChild(Component component, String key) {

    final String childKey = ComponentKeyUtils.getKeyWithSeparator(getGlobalKey(), key);
    final KeyHandler keyHandler = mScopedContext.getKeyHandler();

    /** Null check is for testing only, the keyHandler should never be null here otherwise. */
    if (keyHandler == null) {
      return childKey;
    }

    /** If the key is already unique, return it. */
    if (!keyHandler.hasKey(childKey)) {
      return childKey;
    }

    /** The component has a manual key set on it but that key is a duplicate * */
    if (component.mHasManualKey) {
      final ComponentsLogger logger = mScopedContext.getLogger();
      if (logger != null) {
        final LogEvent event = logger.newEvent(EVENT_WARNING);
        event.addParam(
            PARAM_MESSAGE,
            "The manual key "
                + key
                + " you are setting on this "
                + component.getSimpleName()
                + " is a duplicate and will be changed into a unique one. "
                + "This will result in unexpected behavior if you don't change it.");
        logger.log(event);
      }
    }

    final String childType = component.getSimpleName();

    if (mChildCounters == null) {
      mChildCounters = new HashMap<>();
    }

    /**
     * If the key is a duplicate, we append an index based on the child component's type that would
     * uniquely identify it.
     */
    int childIndex = mChildCounters.containsKey(childType) ? mChildCounters.get(childType) : 0;

    String uniqueKey = ComponentKeyUtils.getKeyForChildPosition(childKey, childIndex);

    mChildCounters.put(childType, childIndex + 1);

    return uniqueKey;
  }

  Component makeCopyWithNullContext() {
    try {
      final Component component = (Component) super.clone();
      component.mScopedContext = null;
      return component;
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  public Component makeShallowCopy() {
    try {
      final Component component = (Component) super.clone();
      component.mIsLayoutStarted = false;
      if (!ComponentsConfiguration.lazyInitializeComponent) {
        component.mChildCounters = new HashMap<>();
      }
      component.mHasManualKey = false;

      return component;
    } catch (CloneNotSupportedException e) {
      // This class implements Cloneable, so this is impossible
      throw new RuntimeException(e);
    }
  }

  Component makeShallowCopyWithNewId() {
    final Component component = makeShallowCopy();
    component.mId = sIdGenerator.incrementAndGet();
    return component;
  }

  boolean hasCachedLayout() {
    return (mLastMeasuredLayout != null);
  }

  InternalNode getCachedLayout() {
    return mLastMeasuredLayout;
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  protected void releaseCachedLayout() {
    if (mLastMeasuredLayout != null) {
      LayoutState.releaseNodeTree(mLastMeasuredLayout, true /* isNestedTree */);
      mLastMeasuredLayout = null;
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  protected void clearCachedLayout() {
    mLastMeasuredLayout = null;
  }

  void release() {
    mIsLayoutStarted = false;
  }

  /**
   * Measure a component with the given {@link SizeSpec} constrain.
   *
   * @param c {@link ComponentContext}.
   * @param widthSpec Width {@link SizeSpec} constrain.
   * @param heightSpec Height {@link SizeSpec} constrain.
   * @param outputSize Size object that will be set with the measured dimensions.
   */
  public void measure(ComponentContext c, int widthSpec, int heightSpec, Size outputSize) {
    releaseCachedLayout();

    mLastMeasuredLayout = LayoutState.createAndMeasureTreeForComponent(
        c,
        this,
        widthSpec,
        heightSpec);

    // This component resolution won't be deferred nor onMeasure called if it's a layout spec.
    // In that case it needs to manually save the latest saze specs.
    // The size specs will be checked during the calculation (or collection) of the main tree.
    if (Component.isLayoutSpec(this)) {
      mLastMeasuredLayout.setLastWidthSpec(widthSpec);
      mLastMeasuredLayout.setLastHeightSpec(heightSpec);
    }

    outputSize.width = mLastMeasuredLayout.getWidth();
    outputSize.height = mLastMeasuredLayout.getHeight();
  }

  protected void copyInterStageImpl(Component component) {

  }

  static boolean isHostSpec(Component component) {
    return (component instanceof HostComponent);
  }

  static boolean isLayoutSpec(Component component) {
    return (component != null && component.getMountType() == MountType.NONE);
  }

  static boolean isMountSpec(Component component) {
    return (component != null && component.getMountType() != MountType.NONE);
  }

  static boolean isMountDrawableSpec(Component component) {
    return (component != null && component.getMountType() == MountType.DRAWABLE);
  }

  static boolean isMountViewSpec(Component component) {
    return (component != null && component.getMountType() == MountType.VIEW);
  }

  static boolean isLayoutSpecWithSizeSpec(Component component) {
    return (isLayoutSpec(component) && component.canMeasure());
  }

  static boolean isNestedTree(Component component) {
    return (isLayoutSpecWithSizeSpec(component)
        || (component != null && component.hasCachedLayout()));
  }

  /**
   * @return whether the given component will render because it returns non-null from its resolved
   *     onCreateLayout, based on its current props and state. Returns true if the resolved layout
   *     is non-null, otherwise false.
   */
  public static boolean willRender(ComponentContext c, Component component) {
    if (component == null) {
      return false;
    }

    component.mLayoutCreatedInWillRender = Layout.create(c, component);
    return willRender(component.mLayoutCreatedInWillRender);
  }

  private static boolean willRender(InternalNode node) {
    if (node == null || ComponentContext.NULL_LAYOUT.equals(node)) {
      return false;
    }

    if (node.isNestedTreeHolder()) {
      // Components using @OnCreateLayoutWithSizeSpec are lazily resolved after the rest of the tree
      // has been measured (so that we have the proper measurements to pass in). This means we can't
      // eagerly check the result of OnCreateLayoutWithSizeSpec.
      throw new IllegalArgumentException(
          "Cannot check willRender on a component that uses @OnCreateLayoutWithSizeSpec! "
              + "Try wrapping this component in one that uses @OnCreateLayout if possible.");
    }

    return true;
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  protected void generateKey(ComponentContext c) {
    if (ComponentsConfiguration.isDebugModeEnabled || ComponentsConfiguration.useGlobalKeys) {
      final Component parentScope = c.getComponentScope();
      final String key = getKey();
      setGlobalKey(
          parentScope == null ? key : parentScope.generateUniqueGlobalKeyForChild(this, key));
    }
  }

  /**
   * Prepares a component for calling any pending state updates on it by setting the TreeProps which
   * the component requires from its parent, setting a scoped component context and applies the
   * pending state updates.
   *
   * @param c component context
   */
  void applyStateUpdates(ComponentContext c) {
    setScopedContext(ComponentContext.withComponentScope(c, this));

    populateTreeProps(getScopedContext().getTreeProps());

    if (ComponentsConfiguration.isDebugModeEnabled || ComponentsConfiguration.useGlobalKeys) {
      final KeyHandler keyHandler = getScopedContext().getKeyHandler();
      // This is for testing, the keyHandler should never be null here otherwise.
      if (keyHandler != null && !ComponentsConfiguration.isEndToEndTestRun) {
        keyHandler.registerKey(this);
      }
    }

    if (hasState()) {
      c.getStateHandler().applyStateUpdatesForComponent(this);
    }
  }

  @Override
  public void recordEventTrigger(EventTriggersContainer container) {
    // Do nothing by default
  }

  boolean isInternalComponent() {
    return false;
  }

  CommonPropsCopyable getCommonPropsCopyable() {
    return mCommonPropsHolder;
  }

  @VisibleForTesting
  CommonProps getCommonProps() {
    return mCommonPropsHolder;
  }

  private CommonPropsHolder getOrCreateCommonPropsHolder() {
    if (mCommonPropsHolder == null) {
      mCommonPropsHolder = new CommonPropsHolder();
    }

    return mCommonPropsHolder;
  }

  @Deprecated
  @Override
  public EventDispatcher getEventDispatcher() {
    return this;
  }

  /**
   * @param <T> the type of this builder. Required to ensure methods defined here in the abstract
   *     class correctly return the type of the concrete subclass.
   */
  public abstract static class Builder<T extends Builder<T>> extends ResourceResolver {

    private ComponentContext mContext;
    private Component mComponent;

    protected void init(
        ComponentContext c,
        @AttrRes int defStyleAttr,
        @StyleRes int defStyleRes,
        Component component) {
      super.init(c, c.getResourceCache());

      mComponent = component;
      mContext = c;

      if (defStyleAttr != 0 || defStyleRes != 0) {
        mComponent.getOrCreateCommonPropsHolder().setStyle(defStyleAttr, defStyleRes);
        component.loadStyle(c, defStyleAttr, defStyleRes);
      }
    }

    public abstract T getThis();

    /** Set a key on the component that is local to its parent. */
    public T key(String key) {
      mComponent.setKey(key);
      return getThis();
    }

    @Override
    protected void release() {
      super.release();

      mContext = null;
      mComponent = null;
    }

    /**
     * Checks that all the required props are supplied, and if not throws a useful exception
     *
     * @param requiredPropsCount expected number of props
     * @param required the bit set that identifies which props have been supplied
     * @param requiredPropsNames the names of all props used for a useful error message
     */
    protected static void checkArgs(
        int requiredPropsCount, BitSet required, String[] requiredPropsNames) {
      if (required != null && required.nextClearBit(0) < requiredPropsCount) {
        List<String> missingProps = new ArrayList<>();
        for (int i = 0; i < requiredPropsCount; i++) {
          if (!required.get(i)) {
            missingProps.add(requiredPropsNames[i]);
          }
        }
        throw new IllegalStateException(
            "The following props are not marked as optional and were not supplied: "
                + Arrays.toString(missingProps.toArray()));
      }
    }

    @ReturnsOwnership
    public abstract Component build();

    /**
     * The RTL/LTR direction of components and text. Determines whether {@link YogaEdge#START} and
     * {@link YogaEdge#END} will resolve to the left or right side, among other things. INHERIT
     * indicates this setting will be inherited from this component's parent.
     *
     * <p>Default: {@link YogaDirection#INHERIT}
     */
    public T layoutDirection(YogaDirection layoutDirection) {
      mComponent.getOrCreateCommonPropsHolder().layoutDirection(layoutDirection);
      return getThis();
    }

    /**
     * Controls how a child aligns in the cross direction, overriding the alignItems of the parent.
     * See https://facebook.github.io/yoga/docs/alignment/ for more information.
     *
     * <p>Default: {@link YogaAlign#AUTO}
     */
    public T alignSelf(YogaAlign alignSelf) {
      mComponent.getOrCreateCommonPropsHolder().alignSelf(alignSelf);
      return getThis();
    }

    /**
     * Controls how this component will be positioned within its parent. See
     * https://facebook.github.io/yoga/docs/absolute-position/ for more details.
     *
     * <p>Default: {@link YogaPositionType#RELATIVE}
     */
    public T positionType(YogaPositionType positionType) {
      mComponent.getOrCreateCommonPropsHolder().positionType(positionType);
      return getThis();
    }

    /**
     * Sets flexGrow, flexShrink, and flexBasis at the same time.
     *
     * <p>When flex is a positive number, it makes the component flexible and it will be sized
     * proportional to its flex value. So a component with flex set to 2 will take twice the space
     * as a component with flex set to 1.
     *
     * <p>When flex is 0, the component is sized according to width and height and it is inflexible.
     *
     * <p>When flex is -1, the component is normally sized according width and height. However, if
     * there's not enough space, the component will shrink to its minWidth and minHeight.
     *
     * <p>See https://facebook.github.io/yoga/docs/flex/ for more information.
     *
     * <p>Default: 0
     */
    public T flex(float flex) {
      mComponent.getOrCreateCommonPropsHolder().flex(flex);
      return getThis();
    }

    /**
     * If the sum of childrens' main axis dimensions is less than the minimum size, how much should
     * this component grow? This value represents the "flex grow factor" and determines how much
     * this component should grow along the main axis in relation to any other flexible children.
     * See https://facebook.github.io/yoga/docs/flex/ for more information.
     *
     * <p>Default: 0
     */
    public T flexGrow(float flexGrow) {
      mComponent.getOrCreateCommonPropsHolder().flexGrow(flexGrow);
      return getThis();
    }

    /**
     * The FlexShrink property describes how to shrink children along the main axis in the case that
     * the total size of the children overflow the size of the container on the main axis. See
     * https://facebook.github.io/yoga/docs/flex/ for more information.
     *
     * <p>Default: 0
     */
    public T flexShrink(float flexShrink) {
      mComponent.getOrCreateCommonPropsHolder().flexShrink(flexShrink);
      return getThis();
    }

    /**
     * The FlexBasis property is an axis-independent way of providing the default size of an item on
     * the main axis. Setting the FlexBasis of a child is similar to setting the Width of that child
     * if its parent is a container with FlexDirection = row or setting the Height of a child if its
     * parent is a container with FlexDirection = column. The FlexBasis of an item is the default
     * size of that item, the size of the item before any FlexGrow and FlexShrink calculations are
     * performed. See https://facebook.github.io/yoga/docs/flex/ for more information.
     *
     * <p>Default: 0
     */
    public T flexBasisPx(@Px int flexBasis) {
      mComponent.getOrCreateCommonPropsHolder().flexBasisPx(flexBasis);
      return getThis();
    }

    /** @see #flexBasisPx */
    public T flexBasisPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().flexBasisPercent(percent);
      return getThis();
    }

    /** @see #flexBasisPx */
    public T flexBasisAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return flexBasisPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #flexBasisPx */
    public T flexBasisAttr(@AttrRes int resId) {
      return flexBasisAttr(resId, 0);
    }

    /** @see #flexBasisPx */
    public T flexBasisRes(@DimenRes int resId) {
      return flexBasisPx(resolveDimenSizeRes(resId));
    }

    /** @see #flexBasisPx */
    public T flexBasisDip(@Dimension(unit = DP) float flexBasis) {
      return flexBasisPx(dipsToPixels(flexBasis));
    }

    public T importantForAccessibility(int importantForAccessibility) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .importantForAccessibility(importantForAccessibility);
      return getThis();
    }

    public T duplicateParentState(boolean duplicateParentState) {
      mComponent.getOrCreateCommonPropsHolder().duplicateParentState(duplicateParentState);
      return getThis();
    }

    public T marginPx(YogaEdge edge, @Px int margin) {
      mComponent.getOrCreateCommonPropsHolder().marginPx(edge, margin);
      return getThis();
    }

    public T marginPercent(YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonPropsHolder().marginPercent(edge, percent);
      return getThis();
    }

    public T marginAuto(YogaEdge edge) {
      mComponent.getOrCreateCommonPropsHolder().marginAuto(edge);
      return getThis();
    }

    public T marginAttr(YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return marginPx(edge, resolveDimenSizeAttr(resId, defaultResId));
    }

    public T marginAttr(YogaEdge edge, @AttrRes int resId) {
      return marginAttr(edge, resId, 0);
    }

    public T marginRes(YogaEdge edge, @DimenRes int resId) {
      return marginPx(edge, resolveDimenSizeRes(resId));
    }

    public T marginDip(YogaEdge edge, @Dimension(unit = DP) float margin) {
      return marginPx(edge, dipsToPixels(margin));
    }

    public T paddingPx(YogaEdge edge, @Px int padding) {
      mComponent.getOrCreateCommonPropsHolder().paddingPx(edge, padding);
      return getThis();
    }

    public T paddingPercent(YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonPropsHolder().paddingPercent(edge, percent);
      return getThis();
    }

    public T paddingAttr(YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return paddingPx(edge, resolveDimenSizeAttr(resId, defaultResId));
    }

    public T paddingAttr(YogaEdge edge, @AttrRes int resId) {
      return paddingAttr(edge, resId, 0);
    }

    public T paddingRes(YogaEdge edge, @DimenRes int resId) {
      return paddingPx(edge, resolveDimenSizeRes(resId));
    }

    public T paddingDip(YogaEdge edge, @Dimension(unit = DP) float padding) {
      return paddingPx(edge, dipsToPixels(padding));
    }

    public T border(Border border) {
      mComponent.getOrCreateCommonPropsHolder().border(border);
      return getThis();
    }

    /**
     * When used in combination with {@link #positionType} of {@link YogaPositionType#ABSOLUTE},
     * allows the component to specify how it should be positioned within its parent. See
     * https://facebook.github.io/yoga/docs/absolute-position/ for more information.
     */
    public T positionPx(YogaEdge edge, @Px int position) {
      mComponent.getOrCreateCommonPropsHolder().positionPx(edge, position);
      return getThis();
    }

    /** @see #positionPx */
    public T positionPercent(YogaEdge edge, float percent) {
      mComponent.getOrCreateCommonPropsHolder().positionPercent(edge, percent);
      return getThis();
    }

    /** @see #positionPx */
    public T positionAttr(YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return positionPx(edge, resolveDimenSizeAttr(resId, defaultResId));
    }

    /** @see #positionPx */
    public T positionAttr(YogaEdge edge, @AttrRes int resId) {
      return positionAttr(edge, resId, 0);
    }

    /** @see #positionPx */
    public T positionRes(YogaEdge edge, @DimenRes int resId) {
      return positionPx(edge, resolveDimenSizeRes(resId));
    }

    /** @see #positionPx */
    public T positionDip(YogaEdge edge, @Dimension(unit = DP) float position) {
      return positionPx(edge, dipsToPixels(position));
    }

    public T widthPx(@Px int width) {
      mComponent.getOrCreateCommonPropsHolder().widthPx(width);
      return getThis();
    }

    public T widthPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().widthPercent(percent);
      return getThis();
    }

    public T widthRes(@DimenRes int resId) {
      return widthPx(resolveDimenSizeRes(resId));
    }

    public T widthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return widthPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T widthAttr(@AttrRes int resId) {
      return widthAttr(resId, 0);
    }

    public T widthDip(@Dimension(unit = DP) float width) {
      return widthPx(dipsToPixels(width));
    }

    public T minWidthPx(@Px int minWidth) {
      mComponent.getOrCreateCommonPropsHolder().minWidthPx(minWidth);
      return getThis();
    }

    public T minWidthPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().minWidthPercent(percent);
      return getThis();
    }

    public T minWidthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return minWidthPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T minWidthAttr(@AttrRes int resId) {
      return minWidthAttr(resId, 0);
    }

    public T minWidthRes(@DimenRes int resId) {
      return minWidthPx(resolveDimenSizeRes(resId));
    }

    public T minWidthDip(@Dimension(unit = DP) float minWidth) {
      return minWidthPx(dipsToPixels(minWidth));
    }

    public T maxWidthPx(@Px int maxWidth) {
      mComponent.getOrCreateCommonPropsHolder().maxWidthPx(maxWidth);
      return getThis();
    }

    public T maxWidthPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().maxWidthPercent(percent);
      return getThis();
    }

    public T maxWidthAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return maxWidthPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T maxWidthAttr(@AttrRes int resId) {
      return maxWidthAttr(resId, 0);
    }

    public T maxWidthRes(@DimenRes int resId) {
      return maxWidthPx(resolveDimenSizeRes(resId));
    }

    public T maxWidthDip(@Dimension(unit = DP) float maxWidth) {
      return maxWidthPx(dipsToPixels(maxWidth));
    }

    public T heightPx(@Px int height) {
      mComponent.getOrCreateCommonPropsHolder().heightPx(height);
      return getThis();
    }

    public T heightPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().heightPercent(percent);
      return getThis();
    }

    public T heightRes(@DimenRes int resId) {
      return heightPx(resolveDimenSizeRes(resId));
    }

    public T heightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return heightPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T heightAttr(@AttrRes int resId) {
      return heightAttr(resId, 0);
    }

    public T heightDip(@Dimension(unit = DP) float height) {
      return heightPx(dipsToPixels(height));
    }

    public T minHeightPx(@Px int minHeight) {
      mComponent.getOrCreateCommonPropsHolder().minHeightPx(minHeight);
      return getThis();
    }

    public T minHeightPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().minHeightPercent(percent);
      return getThis();
    }

    public T minHeightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return minHeightPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T minHeightAttr(@AttrRes int resId) {
      return minHeightAttr(resId, 0);
    }

    public T minHeightRes(@DimenRes int resId) {
      return minHeightPx(resolveDimenSizeRes(resId));
    }

    public T minHeightDip(@Dimension(unit = DP) float minHeight) {
      return minHeightPx(dipsToPixels(minHeight));
    }

    public T maxHeightPx(@Px int maxHeight) {
      mComponent.getOrCreateCommonPropsHolder().maxHeightPx(maxHeight);
      return getThis();
    }

    public T maxHeightPercent(float percent) {
      mComponent.getOrCreateCommonPropsHolder().maxHeightPercent(percent);
      return getThis();
    }

    public T maxHeightAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return maxHeightPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T maxHeightAttr(@AttrRes int resId) {
      return maxHeightAttr(resId, 0);
    }

    public T maxHeightRes(@DimenRes int resId) {
      return maxHeightPx(resolveDimenSizeRes(resId));
    }

    public T maxHeightDip(@Dimension(unit = DP) float maxHeight) {
      return maxHeightPx(dipsToPixels(maxHeight));
    }

    public T aspectRatio(float aspectRatio) {
      mComponent.getOrCreateCommonPropsHolder().aspectRatio(aspectRatio);
      return getThis();
    }

    public T touchExpansionPx(YogaEdge edge, @Px int touchExpansion) {
      mComponent.getOrCreateCommonPropsHolder().touchExpansionPx(edge, touchExpansion);
      return getThis();
    }

    public T touchExpansionAttr(YogaEdge edge, @AttrRes int resId, @DimenRes int defaultResId) {
      return touchExpansionPx(edge, resolveDimenSizeAttr(resId, defaultResId));
    }

    public T touchExpansionAttr(YogaEdge edge, @AttrRes int resId) {
      return touchExpansionAttr(edge, resId, 0);
    }

    public T touchExpansionRes(YogaEdge edge, @DimenRes int resId) {
      return touchExpansionPx(edge, resolveDimenSizeRes(resId));
    }

    public T touchExpansionDip(YogaEdge edge, @Dimension(unit = DP) float touchExpansion) {
      return touchExpansionPx(edge, dipsToPixels(touchExpansion));
    }

    /** @deprecated just use {@link #background(Drawable)} instead. */
    @Deprecated
    public T background(Reference<? extends Drawable> background) {
      mComponent.getOrCreateCommonPropsHolder().background(background);
      return getThis();
    }

    public T background(Reference.Builder<? extends Drawable> builder) {
      return background(builder.build());
    }

    public T background(Drawable background) {
      return background(DrawableReference.create().drawable(background));
    }

    public T backgroundAttr(@AttrRes int resId, @DrawableRes int defaultResId) {
      return backgroundRes(resolveResIdAttr(resId, defaultResId));
    }

    public T backgroundAttr(@AttrRes int resId) {
      return backgroundAttr(resId, 0);
    }

    public T backgroundRes(@DrawableRes int resId) {
      if (resId == 0) {
        return background((Reference<? extends Drawable>) null);
      }

      return background(mContext.getResources().getDrawable(resId));
    }

    public T backgroundColor(@ColorInt int backgroundColor) {
      return background(new ColorDrawable(backgroundColor));
    }

    public T foreground(Drawable foreground) {
      mComponent.getOrCreateCommonPropsHolder().foreground(foreground);
      return getThis();
    }

    public T foregroundAttr(@AttrRes int resId, @DrawableRes int defaultResId) {
      return foregroundRes(resolveResIdAttr(resId, defaultResId));
    }

    public T foregroundAttr(@AttrRes int resId) {
      return foregroundAttr(resId, 0);
    }

    public T foregroundRes(@DrawableRes int resId) {
      if (resId == 0) {
        return foreground(null);
      }

      return foreground(mContext.getResources().getDrawable(resId));
    }

    public T foregroundColor(@ColorInt int foregroundColor) {
      return foreground(new ColorDrawable(foregroundColor));
    }

    public T wrapInView() {
      mComponent.getOrCreateCommonPropsHolder().wrapInView();
      return getThis();
    }

    public T clickHandler(EventHandler<ClickEvent> clickHandler) {
      mComponent.getOrCreateCommonPropsHolder().clickHandler(clickHandler);
      return getThis();
    }

    public T longClickHandler(EventHandler<LongClickEvent> longClickHandler) {
      mComponent.getOrCreateCommonPropsHolder().longClickHandler(longClickHandler);
      return getThis();
    }

    public T focusChangeHandler(EventHandler<FocusChangedEvent> focusChangeHandler) {
      mComponent.getOrCreateCommonPropsHolder().focusChangeHandler(focusChangeHandler);
      return getThis();
    }

    public T touchHandler(EventHandler<TouchEvent> touchHandler) {
      mComponent.getOrCreateCommonPropsHolder().touchHandler(touchHandler);
      return getThis();
    }

    public T interceptTouchHandler(EventHandler<InterceptTouchEvent> interceptTouchHandler) {
      mComponent.getOrCreateCommonPropsHolder().interceptTouchHandler(interceptTouchHandler);
      return getThis();
    }

    public T focusable(boolean isFocusable) {
      mComponent.getOrCreateCommonPropsHolder().focusable(isFocusable);
      return getThis();
    }

    public T enabled(boolean isEnabled) {
      mComponent.getOrCreateCommonPropsHolder().enabled(isEnabled);
      return getThis();
    }

    public T selected(boolean isSelected) {
      mComponent.getOrCreateCommonPropsHolder().selected(isSelected);
      return getThis();
    }

    public T visibleHeightRatio(float visibleHeightRatio) {
      mComponent.getOrCreateCommonPropsHolder().visibleHeightRatio(visibleHeightRatio);
      return getThis();
    }

    public T visibleWidthRatio(float visibleWidthRatio) {
      mComponent.getOrCreateCommonPropsHolder().visibleWidthRatio(visibleWidthRatio);
      return getThis();
    }

    public T visibleHandler(EventHandler<VisibleEvent> visibleHandler) {
      mComponent.getOrCreateCommonPropsHolder().visibleHandler(visibleHandler);
      return getThis();
    }

    public T focusedHandler(EventHandler<FocusedVisibleEvent> focusedHandler) {
      mComponent.getOrCreateCommonPropsHolder().focusedHandler(focusedHandler);
      return getThis();
    }

    public T unfocusedHandler(EventHandler<UnfocusedVisibleEvent> unfocusedHandler) {
      mComponent.getOrCreateCommonPropsHolder().unfocusedHandler(unfocusedHandler);
      return getThis();
    }

    public T fullImpressionHandler(EventHandler<FullImpressionVisibleEvent> fullImpressionHandler) {
      mComponent.getOrCreateCommonPropsHolder().fullImpressionHandler(fullImpressionHandler);
      return getThis();
    }

    public T invisibleHandler(EventHandler<InvisibleEvent> invisibleHandler) {
      mComponent.getOrCreateCommonPropsHolder().invisibleHandler(invisibleHandler);
      return getThis();
    }

    public T contentDescription(CharSequence contentDescription) {
      mComponent.getOrCreateCommonPropsHolder().contentDescription(contentDescription);
      return getThis();
    }

    public T contentDescription(@StringRes int stringId) {
      return contentDescription(mContext.getResources().getString(stringId));
    }

    public T contentDescription(@StringRes int stringId, Object... formatArgs) {
      return contentDescription(mContext.getResources().getString(stringId, formatArgs));
    }

    public T viewTag(Object viewTag) {
      mComponent.getOrCreateCommonPropsHolder().viewTag(viewTag);
      return getThis();
    }

    public T viewTags(SparseArray<Object> viewTags) {
      mComponent.getOrCreateCommonPropsHolder().viewTags(viewTags);
      return getThis();
    }

    /**
     * Shadow elevation and outline provider methods are only functional on {@link
     * android.os.Build.VERSION_CODES#LOLLIPOP} and above.
     */
    public T shadowElevationPx(float shadowElevation) {
      mComponent.getOrCreateCommonPropsHolder().shadowElevationPx(shadowElevation);
      return getThis();
    }

    public T shadowElevationAttr(@AttrRes int resId, @DimenRes int defaultResId) {
      return shadowElevationPx(resolveDimenSizeAttr(resId, defaultResId));
    }

    public T shadowElevationAttr(@AttrRes int resId) {
      return shadowElevationAttr(resId, 0);
    }

    public T shadowElevationRes(@DimenRes int resId) {
      return shadowElevationPx(resolveDimenSizeRes(resId));
    }

    public T shadowElevationDip(@Dimension(unit = DP) float shadowElevation) {
      return shadowElevationPx(dipsToPixels(shadowElevation));
    }

    public T outlineProvider(ViewOutlineProvider outlineProvider) {
      mComponent.getOrCreateCommonPropsHolder().outlineProvider(outlineProvider);
      return getThis();
    }

    public T clipToOutline(boolean clipToOutline) {
      mComponent.getOrCreateCommonPropsHolder().clipToOutline(clipToOutline);
      return getThis();
    }

    public T testKey(String testKey) {
      mComponent.getOrCreateCommonPropsHolder().testKey(testKey);
      return getThis();
    }

    public T accessibilityRole(@AccessibilityRole.AccessibilityRoleType String role) {
      mComponent.getOrCreateCommonPropsHolder().accessibilityRole(role);
      return getThis();
    }

    public T dispatchPopulateAccessibilityEventHandler(
        EventHandler<DispatchPopulateAccessibilityEventEvent>
            dispatchPopulateAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .dispatchPopulateAccessibilityEventHandler(dispatchPopulateAccessibilityEventHandler);
      return getThis();
    }

    public T onInitializeAccessibilityEventHandler(
        EventHandler<OnInitializeAccessibilityEventEvent> onInitializeAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .onInitializeAccessibilityEventHandler(onInitializeAccessibilityEventHandler);
      return getThis();
    }

    public T onInitializeAccessibilityNodeInfoHandler(
        EventHandler<OnInitializeAccessibilityNodeInfoEvent>
            onInitializeAccessibilityNodeInfoHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .onInitializeAccessibilityNodeInfoHandler(onInitializeAccessibilityNodeInfoHandler);
      return getThis();
    }

    public T onPopulateAccessibilityEventHandler(
        EventHandler<OnPopulateAccessibilityEventEvent> onPopulateAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .onPopulateAccessibilityEventHandler(onPopulateAccessibilityEventHandler);
      return getThis();
    }

    public T onRequestSendAccessibilityEventHandler(
        EventHandler<OnRequestSendAccessibilityEventEvent> onRequestSendAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .onRequestSendAccessibilityEventHandler(onRequestSendAccessibilityEventHandler);
      return getThis();
    }

    public T performAccessibilityActionHandler(
        EventHandler<PerformAccessibilityActionEvent> performAccessibilityActionHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .performAccessibilityActionHandler(performAccessibilityActionHandler);
      return getThis();
    }

    public T sendAccessibilityEventHandler(
        EventHandler<SendAccessibilityEventEvent> sendAccessibilityEventHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .sendAccessibilityEventHandler(sendAccessibilityEventHandler);
      return getThis();
    }

    public T sendAccessibilityEventUncheckedHandler(
        EventHandler<SendAccessibilityEventUncheckedEvent> sendAccessibilityEventUncheckedHandler) {
      mComponent
          .getOrCreateCommonPropsHolder()
          .sendAccessibilityEventUncheckedHandler(sendAccessibilityEventUncheckedHandler);
      return getThis();
    }

    public T transitionKey(String key) {
      mComponent.getOrCreateCommonPropsHolder().transitionKey(key);
      return getThis();
    }

    /** Sets the alpha (opacity) of this component. */
    public T alpha(float alpha) {
      mComponent.getOrCreateCommonPropsHolder().alpha(alpha);
      return getThis();
    }

    /**
     * Sets the scale (scaleX and scaleY) on this component. This is mostly relevant for animations
     * and being able to animate size changes. Otherwise for non-animation usecases, you should use
     * the standard layout properties to control the size of your component.
     */
    public T scale(float scale) {
      mComponent.getOrCreateCommonPropsHolder().scale(scale);
      return getThis();
    }

    /**
     * Ports {@link android.view.View#setStateListAnimator(android.animation.StateListAnimator)}
     * into components world. However, since the aforementioned view's method is available only on
     * API 21 and above, calling this method on lower APIs will have no effect. On the legit
     * versions, on the other hand, calling this method will lead to the component being wrapped
     * into a view
     */
    public T stateListAnimator(StateListAnimator stateListAnimator) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mComponent.getOrCreateCommonPropsHolder().stateListAnimator(stateListAnimator);
      }
      return getThis();
    }

    /**
     * Ports {@link android.view.View#setStateListAnimator(android.animation.StateListAnimator)}
     * into components world. However, since the aforementioned view's method is available only on
     * API 21 and above, calling this method on lower APIs will have no effect. On the legit
     * versions, on the other hand, calling this method will lead to the component being wrapped
     * into a view
     */
    public T stateListAnimatorRes(@DrawableRes int resId) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        return stateListAnimator(AnimatorInflater.loadStateListAnimator(mContext, resId));
      }
      return getThis();
    }
  }

  public abstract static class ContainerBuilder<T extends ContainerBuilder<T>> extends Builder<T> {
    public abstract T child(Component child);

    public abstract T child(Component.Builder<?> child);

    /**
     * The AlignSelf property has the same options and effect as AlignItems but instead of affecting
     * the children within a container, you can apply this property to a single child to change its
     * alignment within its parent. See https://facebook.github.io/yoga/docs/alignment/ for more
     * information.
     *
     * <p>Default: {@link YogaAlign#AUTO}
     */
    public abstract T alignContent(YogaAlign alignContent);

    /**
     * The AlignItems property describes how to align children along the cross axis of their
     * container. AlignItems is very similar to JustifyContent but instead of applying to the main
     * axis, it applies to the cross axis. See https://facebook.github.io/yoga/docs/alignment/ for
     * more information.
     *
     * <p>Default: {@link YogaAlign#STRETCH}
     */
    public abstract T alignItems(YogaAlign alignItems);

    /**
     * The JustifyContent property describes how to align children within the main axis of a
     * container. For example, you can use this property to center a child horizontally within a
     * container with FlexDirection = Row or vertically within one with FlexDirection = Column. See
     * https://facebook.github.io/yoga/docs/justify-content/ for more information.
     *
     * <p>Default: {@link YogaJustify#FLEX_START}
     */
    public abstract T justifyContent(YogaJustify justifyContent);

    /**
     * The FlexWrap property is set on containers and controls what happens when children overflow
     * the size of the container along the main axis. If a container specifies {@link YogaWrap#WRAP}
     * then its children will wrap to the next line instead of overflowing.
     *
     * <p>The next line will have the same FlexDirection as the first line and will appear next to
     * the first line along the cross axis - below it if using FlexDirection = Column and to the
     * right if using FlexDirection = Row. See https://facebook.github.io/yoga/docs/flex-wrap/ for
     * more information.
     *
     * <p>Default: {@link YogaWrap#NO_WRAP}
     */
    public abstract T wrap(YogaWrap wrap);

    /** Set this to true if you want the container to be laid out in reverse. */
    public abstract T reverse(boolean reverse);
  }
}
