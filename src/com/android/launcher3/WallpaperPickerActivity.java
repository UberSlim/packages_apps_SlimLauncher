/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.animation.LayoutTransition;
import android.app.ActionBar;
import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;

import com.android.photos.BitmapRegionTileSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class WallpaperPickerActivity extends WallpaperCropActivity {
    static final String TAG = "Launcher.WallpaperPickerActivity";

    private static final int IMAGE_PICK = 5;
    private static final int PICK_WALLPAPER_THIRD_PARTY_ACTIVITY = 6;
    private static final int PICK_LIVE_WALLPAPER = 7;
    private static final String TEMP_WALLPAPER_TILES = "TEMP_WALLPAPER_TILES";

    private ArrayList<Drawable> mBundledWallpaperThumbs;
    private ArrayList<Integer> mBundledWallpaperResIds;
    private Resources mWallpaperResources;

    private View mSelectedThumb;
    private boolean mIgnoreNextTap;
    private OnClickListener mThumbnailOnClickListener;

    private LinearLayout mWallpapersView;

    private ActionMode.Callback mActionModeCallback;
    private ActionMode mActionMode;

    private View.OnLongClickListener mLongClickListener;

    ArrayList<Uri> mTempWallpaperTiles = new ArrayList<Uri>();
    private SavedWallpaperImages mSavedImages;
    private WallpaperInfo mLiveWallpaperInfoOnPickerLaunch;

    private static class ThumbnailMetaData {
        public TileType mTileType;
        public Uri mWallpaperUri;
        public int mSavedWallpaperDbId;
        public int mWallpaperResId;
        public LiveWallpaperListAdapter.LiveWallpaperInfo mLiveWallpaperInfo;
        public ResolveInfo mThirdPartyWallpaperPickerInfo;
    }

    // called by onCreate; this is subclassed to overwrite WallpaperCropActivity
    protected void init() {
        setContentView(R.layout.wallpaper_picker);

        mCropView = (CropView) findViewById(R.id.cropView);
        final View wallpaperStrip = findViewById(R.id.wallpaper_strip);
        mCropView.setTouchCallback(new CropView.TouchCallback() {
            LauncherViewPropertyAnimator mAnim;
            public void onTouchDown() {
                if (mAnim != null) {
                    mAnim.cancel();
                }
                if (wallpaperStrip.getTranslationY() == 0) {
                    mIgnoreNextTap = true;
                }
                mAnim = new LauncherViewPropertyAnimator(wallpaperStrip);
                mAnim.translationY(wallpaperStrip.getHeight()).alpha(0f)
                        .setInterpolator(new DecelerateInterpolator(0.75f));
                mAnim.start();
            }
            public void onTap() {
                boolean ignoreTap = mIgnoreNextTap;
                mIgnoreNextTap = false;
                if (!ignoreTap) {
                    if (mAnim != null) {
                        mAnim.cancel();
                    }
                    mAnim = new LauncherViewPropertyAnimator(wallpaperStrip);
                    mAnim.translationY(0).alpha(1f)
                            .setInterpolator(new DecelerateInterpolator(0.75f));
                    mAnim.start();
                }
            }
        });

        mThumbnailOnClickListener = new OnClickListener() {
            public void onClick(View v) {
                if (mActionMode != null) {
                    // When CAB is up, clicking toggles the item instead
                    if (v.isLongClickable()) {
                        mLongClickListener.onLongClick(v);
                    }
                    return;
                }
                if (mSelectedThumb != null) {
                    mSelectedThumb.setSelected(false);
                }

                ThumbnailMetaData meta = (ThumbnailMetaData) v.getTag();
                if (meta.mTileType == TileType.WALLPAPER_RESOURCE ||
                        meta.mTileType == TileType.SAVED_WALLPAPER ||
                        meta.mTileType == TileType.WALLPAPER_URI) {
                    mSelectedThumb = v;
                    v.setSelected(true);
                }
                if (meta.mTileType == TileType.PICK_IMAGE) {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    Utilities.startActivityForResultSafely(
                            WallpaperPickerActivity.this, intent, IMAGE_PICK);
                } else if (meta.mTileType == TileType.WALLPAPER_URI) {
                    mCropView.setTileSource(new BitmapRegionTileSource(WallpaperPickerActivity.this,
                            meta.mWallpaperUri, 1024, 0), null);
                    mCropView.setTouchEnabled(true);
                } else if (meta.mTileType == TileType.SAVED_WALLPAPER) {
                    String imageFilename = mSavedImages.getImageFilename(meta.mSavedWallpaperDbId);
                    File file = new File(getFilesDir(), imageFilename);
                    mCropView.setTileSource(new BitmapRegionTileSource(WallpaperPickerActivity.this,
                            file.getAbsolutePath(), 1024, 0), null);
                    mCropView.moveToLeft();
                    mCropView.setTouchEnabled(false);
                } else if (meta.mTileType == TileType.LIVE_WALLPAPER) {
                    Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                    preview.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            meta.mLiveWallpaperInfo.info.getComponent());
                    WallpaperManager wm =
                            WallpaperManager.getInstance(WallpaperPickerActivity.this);
                    mLiveWallpaperInfoOnPickerLaunch = wm.getWallpaperInfo();
                    Utilities.startActivityForResultSafely(WallpaperPickerActivity.this,
                            preview, PICK_LIVE_WALLPAPER);
                } else if (meta.mTileType == TileType.WALLPAPER_RESOURCE) {
                    BitmapRegionTileSource source = new BitmapRegionTileSource(mWallpaperResources,
                            WallpaperPickerActivity.this, meta.mWallpaperResId, 1024, 0);
                    mCropView.setTileSource(source, null);
                    Point wallpaperSize = WallpaperCropActivity.getDefaultWallpaperSize(
                            getResources(), getWindowManager());
                    RectF crop = WallpaperCropActivity.getMaxCropRect(
                            source.getImageWidth(), source.getImageHeight(),
                            wallpaperSize.x, wallpaperSize.y, false);
                    mCropView.setScale(wallpaperSize.x / crop.width());
                    mCropView.setTouchEnabled(false);
                } else if (meta.mTileType == TileType.THIRD_PARTY_WALLPAPER_PICKER) {
                    ResolveInfo info = meta.mThirdPartyWallpaperPickerInfo;

                    final ComponentName itemComponentName = new ComponentName(
                            info.activityInfo.packageName, info.activityInfo.name);
                    Intent launchIntent = new Intent(Intent.ACTION_SET_WALLPAPER);
                    launchIntent.setComponent(itemComponentName);
                    Utilities.startActivityForResultSafely(WallpaperPickerActivity.this,
                            launchIntent, PICK_WALLPAPER_THIRD_PARTY_ACTIVITY);
                }
            }
        };
        mLongClickListener = new View.OnLongClickListener() {
            // Called when the user long-clicks on someView
            public boolean onLongClick(View view) {
                CheckableFrameLayout c = (CheckableFrameLayout) view;
                c.toggle();

                if (mActionMode != null) {
                    mActionMode.invalidate();
                } else {
                    // Start the CAB using the ActionMode.Callback defined below
                    mActionMode = startActionMode(mActionModeCallback);
                    int childCount = mWallpapersView.getChildCount();
                    for (int i = 0; i < childCount; i++) {
                        mWallpapersView.getChildAt(i).setSelected(false);
                    }
                }
                return true;
            }
        };

        // Populate the built-in wallpapers
        findBundledWallpapers();
        mWallpapersView = (LinearLayout) findViewById(R.id.wallpaper_list);
        ImageAdapter ia = new ImageAdapter(this, mBundledWallpaperThumbs);
        populateWallpapersFromAdapter(
                mWallpapersView, ia, mBundledWallpaperResIds, TileType.WALLPAPER_RESOURCE, false, true);

        // Populate the saved wallpapers
        mSavedImages = new SavedWallpaperImages(this);
        mSavedImages.loadThumbnailsAndImageIdList();
        ArrayList<Drawable> savedWallpaperThumbs = mSavedImages.getThumbnails();
        ArrayList<Integer > savedWallpaperIds = mSavedImages.getImageIds();
        ia = new ImageAdapter(this, savedWallpaperThumbs);
        populateWallpapersFromAdapter(
                mWallpapersView, ia, savedWallpaperIds, TileType.SAVED_WALLPAPER, true, true);

        // Populate the live wallpapers
        final LinearLayout liveWallpapersView = (LinearLayout) findViewById(R.id.live_wallpaper_list);
        final LiveWallpaperListAdapter a = new LiveWallpaperListAdapter(this);
        a.registerDataSetObserver(new DataSetObserver() {
            public void onChanged() {
                liveWallpapersView.removeAllViews();
                populateWallpapersFromAdapter(
                        liveWallpapersView, a, null, TileType.LIVE_WALLPAPER, false, false);
            }
        });

        // Populate the third-party wallpaper pickers
        final LinearLayout thirdPartyWallpapersView =
                (LinearLayout) findViewById(R.id.third_party_wallpaper_list);
        final ThirdPartyWallpaperPickerListAdapter ta =
                new ThirdPartyWallpaperPickerListAdapter(this);
        populateWallpapersFromAdapter(thirdPartyWallpapersView, ta, null,
                TileType.THIRD_PARTY_WALLPAPER_PICKER, false, false);

        // Add a tile for the Gallery
        LinearLayout masterWallpaperList = (LinearLayout) findViewById(R.id.master_wallpaper_list);
        FrameLayout galleryThumbnail = (FrameLayout) getLayoutInflater().
                inflate(R.layout.wallpaper_picker_gallery_item, masterWallpaperList, false);
        setWallpaperItemPaddingToZero(galleryThumbnail);
        masterWallpaperList.addView(galleryThumbnail, 0);

        // Make its background the last photo taken on external storage
        Bitmap lastPhoto = getThumbnailOfLastPhoto();
        if (lastPhoto != null) {
            ImageView galleryThumbnailBg =
                    (ImageView) galleryThumbnail.findViewById(R.id.wallpaper_image);
            galleryThumbnailBg.setImageBitmap(getThumbnailOfLastPhoto());
            int colorOverlay = getResources().getColor(R.color.wallpaper_picker_translucent_gray);
            galleryThumbnailBg.setColorFilter(colorOverlay, PorterDuff.Mode.SRC_ATOP);
        }

        ThumbnailMetaData meta = new ThumbnailMetaData();
        meta.mTileType = TileType.PICK_IMAGE;
        galleryThumbnail.setTag(meta);
        galleryThumbnail.setOnClickListener(mThumbnailOnClickListener);

        // Create smooth layout transitions for when items are deleted
        final LayoutTransition transitioner = new LayoutTransition();
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
        mWallpapersView.setLayoutTransition(transitioner);

        // Action bar
        // Show the custom action bar view
        final ActionBar actionBar = getActionBar();
        actionBar.setCustomView(R.layout.actionbar_set_wallpaper);
        actionBar.getCustomView().setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ThumbnailMetaData meta = (ThumbnailMetaData) mSelectedThumb.getTag();
                        if (meta.mTileType == TileType.PICK_IMAGE) {
                            // shouldn't be selected, but do nothing
                        } else if (meta.mWallpaperUri != null) {
                            boolean finishActivityWhenDone = true;
                            OnBitmapCroppedHandler h = new OnBitmapCroppedHandler() {
                                public void onBitmapCropped(byte[] imageBytes) {
                                    Bitmap thumb = createThumbnail(null, imageBytes, true);
                                    mSavedImages.writeImage(thumb, imageBytes);
                                }
                            };
                            cropImageAndSetWallpaper(meta.mWallpaperUri, h, finishActivityWhenDone);
                        } else if (meta.mSavedWallpaperDbId != 0) {
                            boolean finishActivityWhenDone = true;
                            String imageFilename =
                                    mSavedImages.getImageFilename(meta.mSavedWallpaperDbId);
                            setWallpaper(imageFilename, finishActivityWhenDone);
                        } else if (meta.mWallpaperResId != 0) {
                            boolean finishActivityWhenDone = true;
                            cropImageAndSetWallpaper(mWallpaperResources,
                                    meta.mWallpaperResId, finishActivityWhenDone);
                        }
                    }
                });

        // CAB for deleting items
        mActionModeCallback = new ActionMode.Callback() {
            // Called when the action mode is created; startActionMode() was called
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                // Inflate a menu resource providing context menu items
                MenuInflater inflater = mode.getMenuInflater();
                inflater.inflate(R.menu.cab_delete_wallpapers, menu);
                return true;
            }

            private int numCheckedItems() {
                int childCount = mWallpapersView.getChildCount();
                int numCheckedItems = 0;
                for (int i = 0; i < childCount; i++) {
                    CheckableFrameLayout c = (CheckableFrameLayout) mWallpapersView.getChildAt(i);
                    if (c.isChecked()) {
                        numCheckedItems++;
                    }
                }
                return numCheckedItems;
            }

            // Called each time the action mode is shown. Always called after onCreateActionMode,
            // but may be called multiple times if the mode is invalidated.
            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                int numCheckedItems = numCheckedItems();
                if (numCheckedItems == 0) {
                    mode.finish();
                    return true;
                } else {
                    mode.setTitle(getResources().getQuantityString(
                            R.plurals.number_of_items_selected, numCheckedItems, numCheckedItems));
                    return true;
                }
            }

            // Called when the user selects a contextual menu item
            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.menu_delete) {
                    int childCount = mWallpapersView.getChildCount();
                    ArrayList<View> viewsToRemove = new ArrayList<View>();
                    for (int i = 0; i < childCount; i++) {
                        CheckableFrameLayout c =
                                (CheckableFrameLayout) mWallpapersView.getChildAt(i);
                        if (c.isChecked()) {
                            ThumbnailMetaData meta = (ThumbnailMetaData) c.getTag();
                            mSavedImages.deleteImage(meta.mSavedWallpaperDbId);
                            viewsToRemove.add(c);
                        }
                    }
                    for (View v : viewsToRemove) {
                        mWallpapersView.removeView(v);
                    }
                    mode.finish(); // Action picked, so close the CAB
                    return true;
                } else {
                    return false;
                }
            }

            // Called when the user exits the action mode
            @Override
            public void onDestroyActionMode(ActionMode mode) {
                int childCount = mWallpapersView.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    CheckableFrameLayout c = (CheckableFrameLayout) mWallpapersView.getChildAt(i);
                    c.setChecked(false);
                }
                mSelectedThumb.setSelected(true);
                mActionMode = null;
            }
        };
    }

    protected Bitmap getThumbnailOfLastPhoto() {
        Cursor cursor = MediaStore.Images.Media.query(getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.Images.ImageColumns._ID,
                    MediaStore.Images.ImageColumns.DATE_TAKEN},
                null, null, MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC LIMIT 1");
        Bitmap thumb = null;
        if (cursor.moveToNext()) {
            int id = cursor.getInt(0);
            thumb = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),
                    id, MediaStore.Images.Thumbnails.MINI_KIND, null);
        }
        cursor.close();
        return thumb;
    }

    protected void onStop() {
        super.onStop();
        final View wallpaperStrip = findViewById(R.id.wallpaper_strip);
        if (wallpaperStrip.getTranslationY() > 0) {
            wallpaperStrip.setTranslationY(0);
            wallpaperStrip.setAlpha(1f);
        }
    }

    protected void onSaveInstanceState(Bundle outState) {
        outState.putParcelableArrayList(TEMP_WALLPAPER_TILES, mTempWallpaperTiles);
    }

    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        ArrayList<Uri> uris = savedInstanceState.getParcelableArrayList(TEMP_WALLPAPER_TILES);
        for (Uri uri : uris) {
            addTemporaryWallpaperTile(uri);
        }
    }

    private enum TileType {
        PICK_IMAGE,
        WALLPAPER_RESOURCE,
        WALLPAPER_URI,
        SAVED_WALLPAPER,
        LIVE_WALLPAPER,
        THIRD_PARTY_WALLPAPER_PICKER
        };

    private void populateWallpapersFromAdapter(ViewGroup parent, BaseAdapter adapter,
            ArrayList<Integer> imageIds, TileType tileType, boolean addLongPressHandler, boolean selectFirstTile) {
        for (int i = 0; i < adapter.getCount(); i++) {
            FrameLayout thumbnail = (FrameLayout) adapter.getView(i, null, parent);
            parent.addView(thumbnail, i);

            ThumbnailMetaData meta = new ThumbnailMetaData();
            meta.mTileType = tileType;
            if (tileType == TileType.WALLPAPER_RESOURCE) {
                meta.mWallpaperResId = imageIds.get(i);
            } else if (tileType == TileType.SAVED_WALLPAPER) {
                meta.mSavedWallpaperDbId = imageIds.get(i);
            } else if (tileType == TileType.LIVE_WALLPAPER) {
                meta.mLiveWallpaperInfo =
                        (LiveWallpaperListAdapter.LiveWallpaperInfo) adapter.getItem(i);
            } else if (tileType == TileType.THIRD_PARTY_WALLPAPER_PICKER) {
                meta.mThirdPartyWallpaperPickerInfo = (ResolveInfo) adapter.getItem(i);
            }
            thumbnail.setTag(meta);
            if (addLongPressHandler) {
                addLongPressHandler(thumbnail);
            }
            thumbnail.setOnClickListener(mThumbnailOnClickListener);
            if (i == 0 && selectFirstTile) {
                mThumbnailOnClickListener.onClick(thumbnail);
            }
        }
    }

    private Bitmap createThumbnail(Uri uri, byte[] imageBytes, boolean leftAligned) {
        Resources res = getResources();
        int width = res.getDimensionPixelSize(R.dimen.wallpaperThumbnailWidth);
        int height = res.getDimensionPixelSize(R.dimen.wallpaperThumbnailHeight);

        BitmapCropTask cropTask;
        if (uri != null) {
            cropTask = new BitmapCropTask(uri, null, width, height, false, true, null);
        } else {
            cropTask = new BitmapCropTask(imageBytes, null, width, height, false, true, null);
        }
        Point bounds = cropTask.getImageBounds();
        if (bounds == null) {
            return null;
        }

        RectF cropRect = WallpaperCropActivity.getMaxCropRect(
                bounds.x, bounds.y, width, height, leftAligned);
        cropTask.setCropBounds(cropRect);

        if (cropTask.cropBitmap()) {
            return cropTask.getCroppedBitmap();
        } else {
            return null;
        }
    }

    private void addTemporaryWallpaperTile(Uri uri) {
        mTempWallpaperTiles.add(uri);
        // Add a tile for the image picked from Gallery
        FrameLayout pickedImageThumbnail = (FrameLayout) getLayoutInflater().
                inflate(R.layout.wallpaper_picker_item, mWallpapersView, false);
        setWallpaperItemPaddingToZero(pickedImageThumbnail);

        // Load the thumbnail
        ImageView image = (ImageView) pickedImageThumbnail.findViewById(R.id.wallpaper_image);
        Bitmap thumb = createThumbnail(uri, null, false);
        if (thumb != null) {
            image.setImageBitmap(thumb);
            Drawable thumbDrawable = image.getDrawable();
            thumbDrawable.setDither(true);
        } else {
            Log.e(TAG, "Error loading thumbnail for uri=" + uri);
        }
        mWallpapersView.addView(pickedImageThumbnail, 0);

        ThumbnailMetaData meta = new ThumbnailMetaData();
        meta.mTileType = TileType.WALLPAPER_URI;
        meta.mWallpaperUri = uri;
        pickedImageThumbnail.setTag(meta);
        pickedImageThumbnail.setOnClickListener(mThumbnailOnClickListener);
        mThumbnailOnClickListener.onClick(pickedImageThumbnail);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == IMAGE_PICK && resultCode == RESULT_OK) {
            Uri uri = data.getData();
            addTemporaryWallpaperTile(uri);
        } else if (requestCode == PICK_WALLPAPER_THIRD_PARTY_ACTIVITY) {
            setResult(RESULT_OK);
            finish();
        } else if (requestCode == PICK_LIVE_WALLPAPER) {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            final WallpaperInfo oldLiveWallpaper = mLiveWallpaperInfoOnPickerLaunch;
            WallpaperInfo newLiveWallpaper = wm.getWallpaperInfo();
            // Try to figure out if a live wallpaper was set;
            if (newLiveWallpaper != null &&
                    (oldLiveWallpaper == null ||
                    !oldLiveWallpaper.getComponent().equals(newLiveWallpaper.getComponent()))) {
                // Return if a live wallpaper was set
                setResult(RESULT_OK);
                finish();
            }
        }
    }

    static void setWallpaperItemPaddingToZero(FrameLayout frameLayout) {
        frameLayout.setPadding(0, 0, 0, 0);
        frameLayout.setForeground(new ZeroPaddingDrawable(frameLayout.getForeground()));
    }

    private void addLongPressHandler(View v) {
        v.setOnLongClickListener(mLongClickListener);
    }

    private void findBundledWallpapers() {
        mBundledWallpaperThumbs = new ArrayList<Drawable>(24);
        mBundledWallpaperResIds = new ArrayList<Integer>(24);

        Pair<ApplicationInfo, Integer> r = getWallpaperArrayResourceId();
        if (r != null) {
            try {
                mWallpaperResources = getPackageManager().getResourcesForApplication(r.first);
                addWallpapers(mWallpaperResources, r.first.packageName, r.second);
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
    }

    public Pair<ApplicationInfo, Integer> getWallpaperArrayResourceId() {
        // Context.getPackageName() may return the "original" package name,
        // com.android.launcher3; Resources needs the real package name,
        // com.android.launcher3. So we ask Resources for what it thinks the
        // package name should be.
        final String packageName = getResources().getResourcePackageName(R.array.wallpapers);
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return new Pair<ApplicationInfo, Integer>(info, R.array.wallpapers);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void addWallpapers(Resources resources, String packageName, int listResId) {
        final String[] extras = resources.getStringArray(listResId);
        for (String extra : extras) {
            int res = resources.getIdentifier(extra, "drawable", packageName);
            if (res != 0) {
                final int thumbRes = resources.getIdentifier(extra + "_small",
                        "drawable", packageName);

                if (thumbRes != 0) {
                    mBundledWallpaperThumbs.add(resources.getDrawable(thumbRes));
                    mBundledWallpaperResIds.add(res);
                    // Log.d(TAG, "add: [" + packageName + "]: " + extra + " (" + res + ")");
                }
            } else {
                Log.e(TAG, "Couldn't find wallpaper " + extra);
            }
        }
    }

    static class ZeroPaddingDrawable extends LevelListDrawable {
        public ZeroPaddingDrawable(Drawable d) {
            super();
            addLevel(0, 0, d);
            setLevel(0);
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return true;
        }
    }

    private static class ImageAdapter extends BaseAdapter implements ListAdapter, SpinnerAdapter {
        private LayoutInflater mLayoutInflater;
        private ArrayList<Drawable> mThumbs;

        ImageAdapter(Activity activity, ArrayList<Drawable> thumbs) {
            mLayoutInflater = activity.getLayoutInflater();
            mThumbs = thumbs;
        }

        public int getCount() {
            return mThumbs.size();
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view;

            if (convertView == null) {
                view = mLayoutInflater.inflate(R.layout.wallpaper_picker_item, parent, false);
            } else {
                view = convertView;
            }

            setWallpaperItemPaddingToZero((FrameLayout) view);

            ImageView image = (ImageView) view.findViewById(R.id.wallpaper_image);

            Drawable thumbDrawable = mThumbs.get(position);
            if (thumbDrawable != null) {
                image.setImageDrawable(thumbDrawable);
                thumbDrawable.setDither(true);
            } else {
                Log.e(TAG, "Error decoding thumbnail for wallpaper #" + position);
            }

            return view;
        }
    }
}