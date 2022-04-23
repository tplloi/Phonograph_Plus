/*
 * Copyright (c) 2022 chr_56 & Abou Zeid (kabouzeid) (original author)
 */

package util.phonograph.tageditor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.WhichButton;
import com.afollestad.materialdialogs.actions.DialogActionExtKt;
import com.afollestad.materialdialogs.list.DialogListExtKt;
import com.github.ksoichiro.android.observablescrollview.ObservableScrollView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import kotlin.Unit;
import lib.phonograph.activity.ToolbarActivity;
import player.phonograph.R;
import player.phonograph.misc.DialogAsyncTask;
import player.phonograph.misc.SimpleObservableScrollViewCallbacks;
import player.phonograph.misc.UpdateToastMediaScannerCompletionListener;
import player.phonograph.util.MusicUtil;
import player.phonograph.util.Util;
import util.mdcolor.ColorUtil;
import util.mdcolor.pref.ThemeColor;
import util.mddesign.util.TintHelper;
import util.mddesign.util.ToolbarColorUtil;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public abstract class AbsTagEditorActivity extends ToolbarActivity {

    public static final String EXTRA_ID = "extra_id";
    public static final String EXTRA_PALETTE = "extra_palette";
    private static final String TAG = AbsTagEditorActivity.class.getSimpleName();
    private static final int REQUEST_CODE_SELECT_IMAGE = 1000;

    FloatingActionButton fab;
    ObservableScrollView observableScrollView;
    Toolbar toolbar;
    ImageView image;
    LinearLayout header;
    TagEditorViewModel model;

    private long id;
    private int headerVariableSpace;
    private int paletteColorPrimary;
    private boolean isInNoImageMode;
    private final SimpleObservableScrollViewCallbacks observableScrollViewCallbacks = new SimpleObservableScrollViewCallbacks() {
        @Override
        public void onScrollChanged(int scrollY, boolean b, boolean b2) {
            float alpha;
            if (!isInNoImageMode) {
                alpha = 1 - (float) Math.max(0, headerVariableSpace - scrollY) / headerVariableSpace;
            } else {
                header.setTranslationY(scrollY);
                alpha = 1;
            }
            toolbar.setBackgroundColor(ColorUtil.withAlpha(paletteColorPrimary, alpha));
            image.setTranslationY(scrollY / 2);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(TagEditorViewModel.class);

        super.onCreate(savedInstanceState);
        setContentView(getContentViewLayout());

        fab = findViewById(R.id.play_pause_fab);
        observableScrollView = findViewById(R.id.observableScrollView);
        toolbar = findViewById(R.id.toolbar);
        image = findViewById(R.id.image);
        header = findViewById(R.id.header);

        getIntentExtras();

        model.setSongPaths(
                getSongPaths()
        );
        if (model.getSongPaths() != null && model.getSongPaths().isEmpty()) {
            finish();
            return;
        }

        headerVariableSpace = getResources().getDimensionPixelSize(R.dimen.tagEditorHeaderVariableSpace);

        setUpViews();

        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setTitle(null);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    private void setUpViews() {
        setUpScrollView();
        setUpFab();
        setUpImageView();
    }

    private void setUpScrollView() {
        observableScrollView.setScrollViewCallbacks(observableScrollViewCallbacks);
    }

    @SuppressLint("CheckResult")
    private void setUpImageView() {
        loadCurrentImage();
        final String[] items = new String[]{
                getString(R.string.download_from_last_fm),
                getString(R.string.pick_from_local_storage),
                getString(R.string.web_search),
                getString(R.string.remove_cover)
        };
        image.setOnClickListener(v -> {
            MaterialDialog dialog = new MaterialDialog(AbsTagEditorActivity.this, MaterialDialog.getDEFAULT_BEHAVIOR())
                    .title(R.string.update_image, null);
            DialogListExtKt.listItems(dialog, null, Arrays.asList(items), null, true,
                    this::invoke
            );
            //set button color
            DialogActionExtKt.getActionButton(dialog, WhichButton.POSITIVE).updateTextColor(ThemeColor.accentColor(this));
            dialog.show();
        });
    }

    private void startImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_from_local_storage)), REQUEST_CODE_SELECT_IMAGE);
    }

    protected abstract void loadCurrentImage();

    protected abstract void getImageFromLastFM();

    protected abstract void searchImageOnWeb();

    protected abstract void deleteImage();

    private void setUpFab() {
        fab.setScaleX(0);
        fab.setScaleY(0);
        fab.setEnabled(false);
        fab.setOnClickListener(v -> save());

        TintHelper.setTintAuto(fab, ThemeColor.accentColor(this), true);
    }

    protected abstract void save();

    private void getIntentExtras() {
        Bundle intentExtras = getIntent().getExtras();
        if (intentExtras != null) {
            id = intentExtras.getLong(EXTRA_ID);
        }
    }

    protected abstract View getContentViewLayout();

    @NonNull
    protected abstract List<String> getSongPaths();

    protected void searchWebFor(String... keys) {
        StringBuilder stringBuilder = new StringBuilder();
        for (String key : keys) {
            stringBuilder.append(key);
            stringBuilder.append(" ");
        }
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(SearchManager.QUERY, stringBuilder.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case android.R.id.home:
                super.onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void setNoImageMode() {
        isInNoImageMode = true;
        image.setVisibility(View.GONE);
        image.setEnabled(false);
        observableScrollView.setPadding(0, Util.getActionBarSize(this), 0, 0);
        observableScrollViewCallbacks.onScrollChanged(observableScrollView.getCurrentScrollY(), false, false);

        setColors(getIntent().getIntExtra(EXTRA_PALETTE, ThemeColor.primaryColor(this)));
        toolbar.setBackgroundColor(paletteColorPrimary);
    }

    protected void dataChanged() {
        showFab();
    }

    private void showFab() {
        fab.animate()
                .setDuration(500)
                .setInterpolator(new OvershootInterpolator())
                .scaleX(1)
                .scaleY(1)
                .start();
        fab.setEnabled(true);
    }

    protected void setImageBitmap(@Nullable final Bitmap bitmap, int bgColor) {
        if (bitmap == null) {
            image.setImageResource(R.drawable.default_album_art);
        } else {
            image.setImageBitmap(bitmap);
        }
        setColors(bgColor);
    }

    protected void setColors(int color) {
        paletteColorPrimary = color;
        observableScrollViewCallbacks.onScrollChanged(observableScrollView.getCurrentScrollY(), false, false);
        header.setBackgroundColor(paletteColorPrimary);
        setStatusbarColor(paletteColorPrimary);
        setNavigationbarColor(paletteColorPrimary);
        setTaskDescriptionColor(paletteColorPrimary);

        toolbar.setTitleTextColor(ToolbarColorUtil.toolbarTitleColor(this, color));
    }

    protected void writeValuesToFiles(@NonNull final Map<FieldKey, String> fieldKeyValueMap, @Nullable final ArtworkInfo artworkInfo) {
        Util.hideSoftKeyboard(this);

        new WriteTagsAsyncTask(this).execute(new WriteTagsAsyncTask.LoadingInfo(getSongPaths(), fieldKeyValueMap, artworkInfo));
    }

    private Unit invoke(MaterialDialog dialog1, Integer index, CharSequence text) {
        switch (index) {
            case 0:
                getImageFromLastFM();
                break;
            case 1:
                startImagePicker();
                break;
            case 2:
                searchImageOnWeb();
                break;
            case 3:
                deleteImage();
                break;
        }
        return null;
    }

    private static class WriteTagsAsyncTask extends DialogAsyncTask<WriteTagsAsyncTask.LoadingInfo, Integer, String[]> {
        Context applicationContext;

        public WriteTagsAsyncTask(Context context) {
            super(context);
            applicationContext = context;
        }

        @Override
        protected String[] doInBackground(LoadingInfo... params) {
            try {
                LoadingInfo info = params[0];

                Artwork artwork = null;
                File albumArtFile = null;
                if (info.artworkInfo != null && info.artworkInfo.artwork != null) {
                    try {
                        albumArtFile = MusicUtil.INSTANCE.createAlbumArtFile().getCanonicalFile();
                        info.artworkInfo.artwork.compress(Bitmap.CompressFormat.PNG, 0, new FileOutputStream(albumArtFile));
                        artwork = ArtworkFactory.createArtworkFromFile(albumArtFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                int counter = 0;
                boolean wroteArtwork = false;
                boolean deletedArtwork = false;
                for (String filePath : info.filePaths) {
                    publishProgress(++counter, info.filePaths.size());
                    try {
                        AudioFile audioFile = AudioFileIO.read(new File(filePath));
                        Tag tag = audioFile.getTagOrCreateAndSetDefault();

                        if (info.fieldKeyValueMap != null) {
                            for (Map.Entry<FieldKey, String> entry : info.fieldKeyValueMap.entrySet()) {
                                try {
                                    tag.setField(entry.getKey(), entry.getValue());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                        if (info.artworkInfo != null) {
                            if (info.artworkInfo.artwork == null) {
                                tag.deleteArtworkField();
                                deletedArtwork = true;
                            } else if (artwork != null) {
                                tag.deleteArtworkField();
                                tag.setField(artwork);
                                wroteArtwork = true;
                            }
                        }

                        audioFile.commit();
                    } catch (@NonNull CannotReadException | IOException | CannotWriteException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
                        e.printStackTrace();
                    }
                }

                Context context = getContext();
                if (context != null) {
                    if (wroteArtwork) {
                        MusicUtil.INSTANCE.insertAlbumArt(context, info.artworkInfo.albumId, albumArtFile.getPath());
                    } else if (deletedArtwork) {
                        MusicUtil.INSTANCE.deleteAlbumArt(context, info.artworkInfo.albumId);
                    }
                }

                return info.filePaths.toArray(new String[info.filePaths.size()]);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String[] toBeScanned) {
            super.onPostExecute(toBeScanned);
            scan(toBeScanned);
        }

        @Override
        protected void onCancelled(String[] toBeScanned) {
            super.onCancelled(toBeScanned);
            scan(toBeScanned);
        }

        private void scan(String[] toBeScanned) {
            Context context = getContext();
            MediaScannerConnection.scanFile(applicationContext, toBeScanned, null, context instanceof Activity ? new UpdateToastMediaScannerCompletionListener((Activity) context, toBeScanned) : null);
        }

        @Override
        protected Dialog createDialog(@NonNull Context context) {
            MaterialDialog dialog = new MaterialDialog(context, MaterialDialog.getDEFAULT_BEHAVIOR())
                    .title(R.string.saving_changes, null)
                    .cancelable(false);
            //set button color
            DialogActionExtKt.getActionButton(dialog, WhichButton.POSITIVE).updateTextColor(ThemeColor.accentColor(context));
            return dialog;
        }

        @Override
        protected void onProgressUpdate(@NonNull Dialog dialog, Integer... values) {
            super.onProgressUpdate(dialog, values);
        }

        public static class LoadingInfo {
            public final Collection<String> filePaths;
            @Nullable
            public final Map<FieldKey, String> fieldKeyValueMap;
            @Nullable
            private ArtworkInfo artworkInfo;

            private LoadingInfo(Collection<String> filePaths, @Nullable Map<FieldKey, String> fieldKeyValueMap, @Nullable ArtworkInfo artworkInfo) {
                this.filePaths = filePaths;
                this.fieldKeyValueMap = fieldKeyValueMap;
                this.artworkInfo = artworkInfo;
            }
        }
    }

    public static class ArtworkInfo {
        public final long albumId;
        public final Bitmap artwork;

        public ArtworkInfo(long albumId, Bitmap artwork) {
            this.albumId = albumId;
            this.artwork = artwork;
        }
    }

    protected long getId() {
        return id;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @NonNull Intent imageReturnedIntent) {
        super.onActivityResult(requestCode, resultCode, imageReturnedIntent);
        switch (requestCode) {
            case REQUEST_CODE_SELECT_IMAGE:
                if (resultCode == RESULT_OK) {
                    Uri selectedImage = imageReturnedIntent.getData();
                    loadImageFromFile(selectedImage);
                }
                break;
        }
    }

    protected abstract void loadImageFromFile(Uri selectedFile);


    @Nullable
    protected String getSongTitle() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.TITLE);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getAlbumTitle() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.ALBUM);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getArtistName() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.ARTIST);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getAlbumArtistName() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.ALBUM_ARTIST);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getGenreName() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.GENRE);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getSongYear() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.YEAR);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getTrackNumber() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.TRACK);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected String getLyrics() {
        try {
            return model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirst(FieldKey.LYRICS);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    protected Bitmap getAlbumArt() {
        try {
            Artwork artworkTag = model.getAudioFile(model.getSongPaths().get(0)).getTagOrCreateAndSetDefault().getFirstArtwork();
            if (artworkTag != null) {
                byte[] artworkBinaryData = artworkTag.getBinaryData();
                return BitmapFactory.decodeByteArray(artworkBinaryData, 0, artworkBinaryData.length);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
