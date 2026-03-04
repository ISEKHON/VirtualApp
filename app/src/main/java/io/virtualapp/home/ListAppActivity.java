package io.virtualapp.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.widget.EditText;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.home.adapters.AppPagerAdapter;

/**
 * App selection screen with search filtering.
 */
public class ListAppActivity extends VActivity {

    private Toolbar mToolBar;
    private ViewPager mViewPager;
    private EditText mSearchBox;
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

        mSearchBox = findViewById(R.id.clone_app_search);
        mSearchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterFragments(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
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

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
