package com.sev.atvhome;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Grid of app tiles. Focus is driven by HomeActivity; tiles themselves never take focus. */
final class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder> {

    private final List<AppEntry> apps = new ArrayList<>();
    private final Typeface font;

    TileAdapter(Typeface font) {
        this.font = font;
    }

    void setApps(List<AppEntry> list) {
        apps.clear();
        apps.addAll(list);
        notifyDataSetChanged();
    }

    AppEntry get(int position) {
        return apps.get(position);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        bind(h.itemView, apps.get(position), font);
    }

    /** Shared by grid tiles and dock tiles. */
    static void bind(View tile, AppEntry app, Typeface font) {
        View card = tile.findViewById(R.id.card);
        ImageView banner = tile.findViewById(R.id.banner);
        ImageView icon = tile.findViewById(R.id.icon);
        TextView fallback = tile.findViewById(R.id.fallback_label);
        TextView name = tile.findViewById(R.id.name);
        card.setClipToOutline(true);
        card.setScaleX(1f);
        card.setScaleY(1f);
        name.setAlpha(0f);
        name.setText(app.label);
        name.setTypeface(font);
        if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            fallback.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(app.icon);
            icon.setVisibility(View.VISIBLE);
            fallback.setText(app.label);
            fallback.setTypeface(font);
            fallback.setVisibility(View.VISIBLE);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }
}
