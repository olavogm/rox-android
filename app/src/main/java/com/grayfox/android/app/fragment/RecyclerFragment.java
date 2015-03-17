package com.grayfox.android.app.fragment;

import android.os.Bundle;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.grayfox.android.app.R;

import roboguice.fragment.RoboFragment;
import roboguice.inject.InjectView;

public class RecyclerFragment extends RoboFragment {

    @InjectView(R.id.no_items) private TextView noItemsTextView;
    @InjectView(R.id.list)     private RecyclerView recyclerView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recycler, container, false);
    }

    protected RecyclerView getRecyclerView() {
        return recyclerView;
    }

    protected TextView getNoItemsTextView() {
        return noItemsTextView;
    }
}