package io.virtualapp.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.home.adapters.AppPagerAdapter;
import io.virtualapp.sys.Installd;

/**
 * App selection screen — toolbar with SearchView, install-from-file menu,
 * ViewPager with multi-select fragment.
 */
public class ListAppActivity extends VActivity {

    private static final int REQUEST_GET_FILE = 1;

    private Toolbar mToolBar;
    private ViewPager mViewPager;
    private AppPagerAdapter mPagerAdapter;

    public static void gotoListApp(Activity activity) {
        Intent intent = new Intent(activity, ListAppActivity.class);
        activity.startActivityForResult(intent, VCommends.REQUEST_SELECT_APP);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clone_app);

        mToolBar = findViewById(R.id.clone_app_tool_bar);
        setSupportActionBar(mToolBar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mViewPager = findViewById(R.id.clone_app_view_pager);
        mPagerAdapter = new AppPagerAdapter(getSupportFragmentManager());
        mViewPager.setAdapter(mPagerAdapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_clone_app, menu);

        // Setup SearchView
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_apps_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterFragments(newText);
                return true;
            }
        });
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                filterFragments("");
                return true;
            }
        });

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.menu_install_file) {
            pickApkFromFile();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void filterFragments(String query) {
        for (int i = 0; i < mPagerAdapter.getCount(); i++) {
            Fragment fragment = getSupportFragmentManager()
                    .findFragmentByTag("android:switcher:" + R.id.clone_app_view_pager + ":" + i);
            if (fragment instanceof ListAppFragment) {
                ((ListAppFragment) fragment).filter(query);
            }
        }
    }

    // --- Install from file ---

    private void pickApkFromFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_GET_FILE);
        } catch (Throwable ignored) {
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GET_FILE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            String path = getPathFromUri(this, uri);
            if (path == null) {
                path = copyUriToCache(this, uri);
            }
            if (path == null) {
                Toast.makeText(this, "Unable to access the file", Toast.LENGTH_SHORT).show();
                return;
            }
            Installd.handleRequestFromFile(this, path);
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    private static String getPathFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            String[] projection = {"_data"};
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null) {
                    int idx = cursor.getColumnIndexOrThrow("_data");
                    if (cursor.moveToFirst()) return cursor.getString(idx);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    private static String copyUriToCache(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        try {
            java.io.InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.File cacheFile = new java.io.File(context.getCacheDir(), "temp_install.apk");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer)) > 0) {
                fos.write(buffer, 0, count);
            }
            fos.flush();
            fos.close();
            is.close();
            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
