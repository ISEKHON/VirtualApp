package io.virtualapp.home.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.virtualapp.R;
import io.virtualapp.home.models.AppData;

/**
 * Adapter for the home screen grid showing installed virtual apps.
 */
public class HomeAppAdapter extends RecyclerView.Adapter<HomeAppAdapter.ViewHolder> {

    private final List<AppData> mApps = new ArrayList<>();
    private final LayoutInflater mInflater;
    private OnAppClickListener mListener;

    public interface OnAppClickListener {
        void onAppClick(AppData data, int position);
        void onAppLongClick(AppData data, int position);
    }

    public HomeAppAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    public void setOnAppClickListener(OnAppClickListener listener) {
        mListener = listener;
    }

    public void setData(List<AppData> apps) {
        mApps.clear();
        if (apps != null) {
            mApps.addAll(apps);
        }
        notifyDataSetChanged();
    }

    public List<AppData> getData() {
        return mApps;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_home_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AppData data = mApps.get(position);
        holder.iconView.setImageDrawable(data.getIcon());
        holder.nameView.setText(data.getName());
        holder.progressBar.setVisibility(data.isLoading() ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onAppClick(data, position);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mListener != null) {
                mListener.onAppLongClick(data, position);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView iconView;
        TextView nameView;
        ProgressBar progressBar;

        ViewHolder(View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.home_item_icon);
            nameView = itemView.findViewById(R.id.home_item_name);
            progressBar = itemView.findViewById(R.id.home_item_progress);
        }
    }
}
