package com.zygisk_enc.notivault.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.adapter.AppFilterAdapter;
import com.zygisk_enc.notivault.databinding.FragmentAppsBinding;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;

public class AppsFragment extends Fragment {

    private FragmentAppsBinding binding;
    private NotificationViewModel viewModel;
    private AppFilterAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);

        adapter = new AppFilterAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        adapter.setOnAppClickListener(summary -> {
            viewModel.setFilterPackage(summary.packageName);
            Navigation.findNavController(view).navigate(R.id.navigation_history);
        });

        binding.etSearchApps.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        viewModel.getAppSummaries().observe(getViewLifecycleOwner(), summaries -> {
            if (summaries == null || summaries.isEmpty()) {
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.recyclerView.setVisibility(View.GONE);
            } else {
                binding.emptyState.setVisibility(View.GONE);
                binding.recyclerView.setVisibility(View.VISIBLE);
                adapter.submitList(summaries);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
