package com.zygisk_enc.notivault.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import com.google.android.material.datepicker.MaterialDatePicker;
import java.util.Calendar;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.CompositeDateValidator;

import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.adapter.NotificationAdapter;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.databinding.FragmentHistoryBinding;
import com.zygisk_enc.notivault.util.DateUtils;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.ArrayList;
import java.util.List;

public class HistoryFragment extends Fragment {

    private FragmentHistoryBinding binding;
    private NotificationViewModel viewModel;
    private Long oldestNotificationTimestamp = null;
    private NotificationAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);

        setupRecyclerView();
        setupSearchBar();
        setupSwipeToDelete();
        setupSwipeToRefresh();
        observeNotifications();

        viewModel.getFilterFavorites().observe(getViewLifecycleOwner(), favsOnly -> {
            androidx.appcompat.app.ActionBar actionBar =
                    ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
            if (actionBar != null) {
                if (favsOnly != null && favsOnly) {
                    actionBar.setSubtitle(R.string.pref_favorites_title);
                } else {
                    actionBar.setSubtitle(null);
                }
            }
        });
    }

    private void setupSwipeToRefresh() {
        binding.swipeRefresh.setOnRefreshListener(() -> {
            // Reset filters to show all notifications on refresh
            viewModel.setFilterPackage(null);
            viewModel.setFilterFavorites(false);
            viewModel.setDateFilter(null, null);
            viewModel.setSearchQuery("");
            binding.etSearch.setText("");
            binding.layoutSearchExpanded.setVisibility(View.GONE);
            binding.layoutSearchCollapsed.setVisibility(View.VISIBLE);
            binding.recyclerView.scrollToPosition(0);

            binding.swipeRefresh.postDelayed(() -> {
                binding.swipeRefresh.setRefreshing(false);
            }, 800);
        });
    }

    private void setupRecyclerView() {
        adapter = new NotificationAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(new NotificationAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(NotificationEntity entity) {
                viewModel.markAsRead(entity.id);
                showDetailDialog(entity);
            }

            @Override
            public void onItemLongClick(NotificationEntity entity) {
                String content = entity.title + (entity.text.isEmpty() ? "" : "\n" + entity.text);
                ClipboardManager clipboard = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("notification", content);
                clipboard.setPrimaryClip(clip);
                Snackbar.make(binding.getRoot(), R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT).show();
            }

            @Override
            public void onDeleteClick(NotificationEntity entity) {
                viewModel.deleteById(entity.id);
            }

            @Override
            public void onFavoriteClick(NotificationEntity entity) {
                viewModel.setFavorite(entity.id, !entity.isFavorite);
            }
        });
    }

    private void setupSearchBar() {
        viewModel.getOldestTimestamp().observe(getViewLifecycleOwner(), timestamp -> {
            oldestNotificationTimestamp = timestamp;
        });
        viewModel.getFilterDateStart().observe(getViewLifecycleOwner(), start -> {
            if (start != null) {
                binding.btnOpenCalendar.setColorFilter(requireContext().getColor(android.R.color.holo_red_light));
            } else {
                binding.btnOpenCalendar.clearColorFilter();
            }
        });

        binding.btnOpenCalendar.setOnClickListener(v -> {
            Long currentStart = viewModel.getFilterDateStart().getValue();
            MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
            builder.setTitleText("Select Date");
            if (currentStart != null) builder.setSelection(currentStart);

            CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
            long todayUtc = MaterialDatePicker.todayInUtcMilliseconds();
            
            constraintsBuilder.setEnd(todayUtc);
            
            long startUtc = todayUtc;
            if (oldestNotificationTimestamp != null && oldestNotificationTimestamp > 0) {
                Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                // We want the UTC midnight that corresponds to the local date of the oldest notification
                Calendar localOld = Calendar.getInstance();
                localOld.setTimeInMillis(oldestNotificationTimestamp);
                c.set(localOld.get(Calendar.YEAR), localOld.get(Calendar.MONTH), localOld.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                startUtc = c.getTimeInMillis();
            }
            // Ensure start isn't somehow after end
            if (startUtc > todayUtc) startUtc = todayUtc;
            
            constraintsBuilder.setStart(startUtc);
            
            java.util.List<CalendarConstraints.DateValidator> validators = new java.util.ArrayList<>();
            validators.add(DateValidatorPointBackward.before(todayUtc + 1));
            validators.add(DateValidatorPointForward.from(startUtc - 1));
            constraintsBuilder.setValidator(CompositeDateValidator.allOf(validators));
            
            builder.setCalendarConstraints(constraintsBuilder.build());

            MaterialDatePicker<Long> picker = builder.build();
            picker.addOnPositiveButtonClickListener(selection -> {
                // MaterialDatePicker selection is UTC midnight. We want to interpret that date as LOCAL midnight.
                Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                utc.setTimeInMillis(selection);
                
                Calendar local = Calendar.getInstance();
                local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
                local.set(Calendar.MILLISECOND, 0);
                long start = local.getTimeInMillis();
                local.add(Calendar.DAY_OF_YEAR, 1);
                long end = local.getTimeInMillis() - 1;
                viewModel.setDateFilter(start, end);
            });
            picker.show(getParentFragmentManager(), "DATE_PICKER");
        });
        
        binding.btnOpenCalendar.setOnLongClickListener(v -> {
            if (viewModel.getFilterDateStart().getValue() != null) {
                viewModel.setDateFilter(null, null);
                Snackbar.make(binding.getRoot(), "Date filter cleared", Snackbar.LENGTH_SHORT).show();
            } else {
                Snackbar.make(binding.getRoot(), "Long press to clear date filter", Snackbar.LENGTH_SHORT).show();
            }
            return true;
        });

        binding.btnOpenSearch.setOnClickListener(v -> {
            binding.layoutSearchCollapsed.setVisibility(View.GONE);
            binding.layoutSearchExpanded.setVisibility(View.VISIBLE);
            binding.etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        binding.btnCloseSearch.setOnClickListener(v -> {
            binding.etSearch.setText("");
            binding.layoutSearchExpanded.setVisibility(View.GONE);
            binding.layoutSearchCollapsed.setVisibility(View.VISIBLE);
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(binding.etSearch.getWindowToken(), 0);
            }
        });

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.setSearchQuery(s.toString());
            }
        });

        binding.btnClearAll.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_all_title)
                    .setMessage(R.string.clear_all_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear, (dialog, which) -> viewModel.deleteAll())
                    .show();
        });
    }

    private void setupSwipeToDelete() {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                NotificationAdapter.ListItem item = adapter.getItem(position);
                if (item.type == NotificationAdapter.ListItem.TYPE_NOTIFICATION && item.entity != null) {
                    NotificationEntity entity = item.entity;
                    viewModel.deleteById(entity.id);
                    Snackbar.make(binding.getRoot(), R.string.notification_deleted, Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo, v -> {
                                // Re-insert: we need the entity back
                                // This is simplified - in production, save the entity before deleting
                            })
                            .show();
                }
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                // Disable swipe on headers
                if (rv.getAdapter() != null) {
                    int pos = vh.getAdapterPosition();
                    if (pos >= 0 && pos < adapter.getItemCount()) {
                        if (adapter.getItem(pos).type == NotificationAdapter.ListItem.TYPE_HEADER) {
                            return 0;
                        }
                    }
                }
                return super.getSwipeDirs(rv, vh);
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView);
    }

    private void observeNotifications() {
        viewModel.getNotifications().observe(getViewLifecycleOwner(), notifications -> {
            if (notifications == null || notifications.isEmpty()) {
                showRecyclerView(false);
            } else {
                showRecyclerView(true);
                adapter.submitList(buildListWithHeaders(notifications));
            }
        });
        
        viewModel.getScrollToTopEvent().observe(getViewLifecycleOwner(), scroll -> {
            if (scroll != null && scroll) {
                binding.recyclerView.scrollToPosition(0);
                viewModel.clearScrollToTopEvent();
            }
        });
    }

    private void showRecyclerView(boolean show) {
        if (show) {
            if (binding.recyclerView.getVisibility() != View.VISIBLE) {
                binding.recyclerView.setAlpha(0f);
                binding.recyclerView.setVisibility(View.VISIBLE);
                binding.recyclerView.animate().alpha(1f).setDuration(250).setListener(null);
            }
            if (binding.emptyState.getVisibility() == View.VISIBLE) {
                binding.emptyState.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> binding.emptyState.setVisibility(View.GONE));
            }
        } else {
            if (binding.emptyState.getVisibility() != View.VISIBLE) {
                binding.emptyState.setAlpha(0f);
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.emptyState.animate().alpha(1f).setDuration(250).setListener(null);
            }
            if (binding.recyclerView.getVisibility() == View.VISIBLE) {
                binding.recyclerView.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> binding.recyclerView.setVisibility(View.GONE));
            }
        }
    }

    private List<NotificationAdapter.ListItem> buildListWithHeaders(List<NotificationEntity> notifications) {
        List<NotificationAdapter.ListItem> result = new ArrayList<>();
        String lastGroup = null;
        for (NotificationEntity entity : notifications) {
            String group = DateUtils.getDateGroupKey(entity.timestamp);
            if (!group.equals(lastGroup)) {
                result.add(new NotificationAdapter.ListItem(DateUtils.getRelativeTimeLabel(entity.timestamp)));
                lastGroup = group;
            }
            result.add(new NotificationAdapter.ListItem(entity));
        }
        return result;
    }

    private void showDetailDialog(NotificationEntity entity) {
        String content = entity.bigText != null && !entity.bigText.isEmpty()
                ? entity.bigText : entity.text;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(entity.title.isEmpty() ? entity.appName : entity.title)
                .setMessage(content)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.copy, (d, w) -> {
                    String text = entity.title + "\n" + content;
                    ClipboardManager clipboard = (ClipboardManager)
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("notification", text));
                    Snackbar.make(binding.getRoot(), R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT).show();
                })
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
