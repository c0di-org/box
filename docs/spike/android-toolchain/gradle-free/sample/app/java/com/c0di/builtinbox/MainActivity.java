package com.c0di.builtinbox;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses AppCompat, ConstraintLayout, RecyclerView and Material — every one of
 * them resolved from Maven and merged by hand, with no Gradle anywhere.
 */
public class MainActivity extends AppCompatActivity {

    private final List<String[]> rows = new ArrayList<>();
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ((TextView) findViewById(R.id.build_info)).setText(BuildInfo.TEXT);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(rows);
        list.setAdapter(adapter);

        seedRows();

        MaterialButton button = findViewById(R.id.whereami);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRuntime();
            }
        });
    }

    private void seedRows() {
        rows.clear();
        rows.add(new String[]{"AppCompat", getClass().getSuperclass().getName()});
        rows.add(new String[]{"ConstraintLayout", "androidx.constraintlayout.widget"});
        rows.add(new String[]{"RecyclerView", "this list is one"});
        rows.add(new String[]{"Material", "MaterialCardView + MaterialButton"});
        adapter.notifyDataSetChanged();
    }

    private void showRuntime() {
        rows.clear();
        rows.add(new String[]{"device", Build.MANUFACTURER + " " + Build.MODEL});
        rows.add(new String[]{"android", Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")"});
        rows.add(new String[]{"abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"});
        rows.add(new String[]{"cores", String.valueOf(Runtime.getRuntime().availableProcessors())});
        rows.add(new String[]{"compiled on", "the same phone, emulated"});
        adapter.notifyDataSetChanged();
    }

    /** A real adapter — proves RecyclerView is linked, not merely present. */
    static class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        private final List<String[]> items;

        RowAdapter(List<String[]> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] row = items.get(position);
            h.title.setText(row[0]);
            h.detail.setText(row[1]);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView detail;

            Holder(View v) {
                super(v);
                title = v.findViewById(R.id.row_title);
                detail = v.findViewById(R.id.row_detail);
            }
        }
    }
}
