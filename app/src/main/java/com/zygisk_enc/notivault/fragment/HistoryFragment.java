package com.zygisk_enc.notivault.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.datepicker.MaterialDatePicker;
import java.util.Calendar;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.CompositeDateValidator;
import androidx.core.util.Pair;

import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.BaseActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.adapter.NotificationAdapter;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.BackupUtil;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.RuleDialogHelper;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.databinding.FragmentHistoryBinding;
import com.zygisk_enc.notivault.util.DateUtils;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import com.zygisk_enc.notivault.worker.DriveBackupWorker;
import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

public class HistoryFragment extends Fragment {

    private static final String CLOUD_BACKUP_WORK_TAG = "cloud_backup_periodic";

    private FragmentHistoryBinding binding;
    private NotificationViewModel viewModel;
    private Long oldestNotificationTimestamp = null;
    private NotificationAdapter adapter;
    private int lockedPosition = -1;
    private int lockedOffset = 0;
    private boolean isViewingMessage = false;
    private androidx.activity.OnBackPressedCallback searchBackPressedCallback;
    private final android.os.Handler searchDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchDebounceRunnable;

    // SAF folder picker for cloud backup destination
    private final ActivityResultLauncher<Uri> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                AppLockManager.setExpectingActivityResult(false);
                if (uri == null) return;
                // Take persistent read+write permission so WorkManager can write on schedule
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                PreferenceUtil.setCloudBackupUri(requireContext(), uri.toString());
                showCloudBackupConfigDialog();
            }
    );

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

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

        binding.chipAppRules.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), com.zygisk_enc.notivault.AppRulesActivity.class);
            startActivity(intent);
        });

        binding.chipToastsHistory.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(requireContext(), com.zygisk_enc.notivault.ToastHistoryActivity.class);
            startActivity(intent);
        });

        binding.chipCloudBackup.setOnClickListener(v -> {
            boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(this::showCloudBackupDialog, getString(R.string.auth_cloud_backup));
            } else {
                showCloudBackupDialog();
            }
        });
        binding.chipClearLogs.setOnClickListener(v -> showDeleteCalendar());

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

        binding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layout != null) {
                    int visibleCount = layout.getChildCount();
                    int totalCount = layout.getItemCount();
                    int firstVisiblePos = layout.findFirstVisibleItemPosition();

                    // Prefetch threshold: trigger next page load when 100 items remain
                    if ((visibleCount + firstVisiblePos) >= totalCount - 100 && firstVisiblePos >= 0) {
                        Integer currentLimit = viewModel.getFilterLimit().getValue();
                        // Guard against concurrent multiple updates until current limit is loaded
                        if (currentLimit != null && totalCount >= currentLimit) {
                            // Capture the exact position and offset at trigger time
                            lockedPosition = firstVisiblePos;
                            android.view.View firstVisibleView = layout.findViewByPosition(firstVisiblePos);
                            if (firstVisibleView != null) {
                                lockedOffset = firstVisibleView.getTop() - recyclerView.getPaddingTop();
                            } else {
                                lockedOffset = 0;
                            }
                            viewModel.loadNextPage();
                        }
                    }
                }
            }
        });
    }

    private final Runnable stopRefreshRunnable = () -> {
        if (binding != null) {
            binding.swipeRefresh.setRefreshing(false);
        }
    };

    private void setupSwipeToRefresh() {
        binding.swipeRefresh.setOnRefreshListener(() -> {
            // Reset filters to show all notifications on refresh
            viewModel.resetAllFilters();
            closeSearchBox();
            binding.recyclerView.scrollToPosition(0);

            binding.swipeRefresh.removeCallbacks(stopRefreshRunnable);
            binding.swipeRefresh.postDelayed(stopRefreshRunnable, 600);
        });
    }

    private void setupRecyclerView() {
        adapter = new NotificationAdapter();
        adapter.setShowReadUnreadStatus(PreferenceUtil.isShowReadUnreadEnabled(requireContext()));
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setHasFixedSize(true);
        binding.recyclerView.setItemViewCacheSize(25);
        binding.recyclerView.setAdapter(adapter);

        // Disable change animations to prevent item update animations from shifting scroll position
        androidx.recyclerview.widget.RecyclerView.ItemAnimator animator = binding.recyclerView.getItemAnimator();
        if (animator instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        adapter.setOnItemClickListener(new NotificationAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(NotificationEntity entity) {
                viewModel.markAsRead(entity.id);
                showDetailDialog(entity);
            }

            @Override
            public void onItemLongClick(NotificationEntity entity) {
                String decTitle = EncryptionHelper.decrypt(entity.title);
                String decText = EncryptionHelper.decrypt(entity.text);
                String content = decTitle + (decText.isEmpty() ? "" : "\n" + decText);
                ClipboardManager clipboard = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("notification", content);
                clipboard.setPrimaryClip(clip);
                showAnchoredSnackbar(Snackbar.make(binding.getRoot(), R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT));
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

        // Date filter chip click
        binding.chipFilterDate.setOnClickListener(v -> openDatePicker());
        binding.chipFilterDate.setOnLongClickListener(v -> {
            if (viewModel.getFilterDateStart().getValue() != null) {
                viewModel.setDateFilter(null, null);
                showAnchoredSnackbar(Snackbar.make(binding.getRoot(), R.string.date_filter_cleared, Snackbar.LENGTH_SHORT));
            } else {
                showAnchoredSnackbar(Snackbar.make(binding.getRoot(), R.string.hint_long_press_clear_date, Snackbar.LENGTH_SHORT));
            }
            return true;
        });

        // Observe Date Filter for active chip
        viewModel.getFilterDateStart().observe(getViewLifecycleOwner(), start -> {
            updateActiveFilters();
        });

        // Observe Package Filter for active chip
        viewModel.getFilterPackage().observe(getViewLifecycleOwner(), pkg -> {
            updateActiveFilters();
        });

        searchBackPressedCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeSearchBox();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), searchBackPressedCallback);

        viewModel.getOpenSearchEvent().observe(getViewLifecycleOwner(), open -> {
            if (open != null && open) {
                viewModel.clearOpenSearchEvent();
                binding.getRoot().post(this::openSearchBox);
            }
        });

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s != null ? s.toString().trim() : "";
                if (searchBackPressedCallback != null) {
                    searchBackPressedCallback.setEnabled(!query.isEmpty());
                }

                if (searchDebounceRunnable != null) {
                    searchDebounceHandler.removeCallbacks(searchDebounceRunnable);
                }

                if (query.isEmpty()) {
                    viewModel.setSearchQuery(null);
                } else {
                    searchDebounceRunnable = () -> viewModel.setSearchQuery(query);
                    searchDebounceHandler.postDelayed(searchDebounceRunnable, 100);
                }
            }
        });

        binding.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {

                if (searchDebounceRunnable != null) {
                    searchDebounceHandler.removeCallbacks(searchDebounceRunnable);
                }

                String query = binding.etSearch.getText() != null ? binding.etSearch.getText().toString().trim() : "";
                viewModel.setSearchQuery(query.isEmpty() ? null : query);

                // Hide soft keyboard and clear focus
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(binding.etSearch.getWindowToken(), 0);
                }
                binding.etSearch.clearFocus();
                return true;
            }
            return false;
        });
    }

    private void updateActiveFilters() {
        if (binding == null) return;
        String pkg = viewModel.getFilterPackage().getValue();
        Long dateStart = viewModel.getFilterDateStart().getValue();

        boolean hasActiveFilter = false;

        if (pkg != null && !pkg.isEmpty()) {
            hasActiveFilter = true;
            binding.chipActiveAppFilter.setVisibility(View.VISIBLE);
            binding.chipActiveAppFilter.setText(getString(R.string.filter_app_prefix, pkg));
            binding.chipActiveAppFilter.setOnCloseIconClickListener(v -> viewModel.setFilterPackage(null));
        } else {
            binding.chipActiveAppFilter.setVisibility(View.GONE);
        }

        if (dateStart != null) {
            hasActiveFilter = true;
            binding.chipActiveDateFilter.setVisibility(View.VISIBLE);
            String dateLabel = DateUtils.getRelativeTimeLabel(requireContext(), dateStart);
            binding.chipActiveDateFilter.setText(getString(R.string.filter_date_prefix, dateLabel));
            binding.chipActiveDateFilter.setOnCloseIconClickListener(v -> viewModel.setDateFilter(null, null));
        } else {
            binding.chipActiveDateFilter.setVisibility(View.GONE);
        }

        binding.layoutActiveFilters.setVisibility(hasActiveFilter ? View.VISIBLE : View.GONE);
    }

    private void openDatePicker() {
        Long currentStart = viewModel.getFilterDateStart().getValue();
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setTitleText(getString(R.string.desc_select_date));
        if (currentStart != null) builder.setSelection(currentStart);

        CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
        long todayUtc = MaterialDatePicker.todayInUtcMilliseconds();
        
        constraintsBuilder.setEnd(todayUtc);
        
        long startUtc = todayUtc;
        if (oldestNotificationTimestamp != null && oldestNotificationTimestamp > 0) {
            Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            Calendar localOld = Calendar.getInstance();
            localOld.setTimeInMillis(oldestNotificationTimestamp);
            c.set(localOld.get(Calendar.YEAR), localOld.get(Calendar.MONTH), localOld.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            startUtc = c.getTimeInMillis();
        }
        if (startUtc > todayUtc) startUtc = todayUtc;
        
        constraintsBuilder.setStart(startUtc);
        
        java.util.List<CalendarConstraints.DateValidator> validators = new java.util.ArrayList<>();
        validators.add(DateValidatorPointBackward.before(todayUtc + 1));
        validators.add(DateValidatorPointForward.from(startUtc - 1));
        constraintsBuilder.setValidator(CompositeDateValidator.allOf(validators));
        
        builder.setCalendarConstraints(constraintsBuilder.build());

        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
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
    }

    private void openSearchBox() {
        if (binding == null || !isAdded()) return;
        binding.etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeSearchBox() {
        if (searchDebounceRunnable != null) {
            searchDebounceHandler.removeCallbacks(searchDebounceRunnable);
        }
        if (binding == null) return;
        binding.etSearch.setText("");
        viewModel.setSearchQuery(null);
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.etSearch.getWindowToken(), 0);
        }
        if (searchBackPressedCallback != null) {
            searchBackPressedCallback.setEnabled(false);
        }
    }

    private void showDeleteCalendar() {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setTitleText(getString(R.string.select_date_to_delete_logs));
        builder.setSelection(MaterialDatePicker.todayInUtcMilliseconds());

        CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
        long todayUtc = MaterialDatePicker.todayInUtcMilliseconds();
        constraintsBuilder.setEnd(todayUtc);

        long startUtc = todayUtc;
        if (oldestNotificationTimestamp != null && oldestNotificationTimestamp > 0) {
            Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            Calendar localOld = Calendar.getInstance();
            localOld.setTimeInMillis(oldestNotificationTimestamp);
            c.set(localOld.get(Calendar.YEAR), localOld.get(Calendar.MONTH), localOld.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            startUtc = c.getTimeInMillis();
        }
        if (startUtc > todayUtc) startUtc = todayUtc;
        constraintsBuilder.setStart(startUtc);

        java.util.List<CalendarConstraints.DateValidator> validators = new java.util.ArrayList<>();
        validators.add(DateValidatorPointBackward.before(todayUtc + 1));
        validators.add(DateValidatorPointForward.from(startUtc - 1));
        constraintsBuilder.setValidator(CompositeDateValidator.allOf(validators));

        builder.setCalendarConstraints(constraintsBuilder.build());

        MaterialDatePicker<Long> picker = builder.build();

        // Inject "Delete All" button beside Cancel button on bottom-left
        getParentFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull androidx.fragment.app.FragmentManager fm, @NonNull Fragment f, @NonNull View v, @Nullable Bundle savedInstanceState) {
                if (f == picker) {
                    fm.unregisterFragmentLifecycleCallbacks(this);
                    View cancelButton = v.findViewById(com.google.android.material.R.id.cancel_button);
                    if (cancelButton != null && cancelButton.getParent() instanceof ViewGroup) {
                        ViewGroup buttonContainer = (ViewGroup) cancelButton.getParent();
                        
                        com.google.android.material.button.MaterialButton btnDeleteAll = 
                                new com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
                        btnDeleteAll.setText(R.string.clear_all);
                        int errorColor = com.google.android.material.color.MaterialColors.getColor(
                                requireContext(), com.google.android.material.R.attr.colorError, android.graphics.Color.RED);
                        btnDeleteAll.setTextColor(errorColor);
                        
                        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
                        btnDeleteAll.setLayoutParams(lp);
                        
                        btnDeleteAll.setOnClickListener(delView -> {
                            picker.dismiss();
                            confirmDeleteAll();
                        });
                        
                        buttonContainer.addView(btnDeleteAll, 0);
                    }
                }
            }
        }, false);

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;
            Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            utc.setTimeInMillis(selection);
            
            Calendar local = Calendar.getInstance();
            local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
            local.set(Calendar.MILLISECOND, 0);
            long start = local.getTimeInMillis();
            local.add(Calendar.DAY_OF_YEAR, 1);
            long end = local.getTimeInMillis() - 1;

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault());
            String formattedDate = sdf.format(new java.util.Date(start));

            Runnable proceedToDeleteDate = () -> {
                BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.delete_logs_for_date_title, formattedDate))
                        .setMessage(getString(R.string.delete_logs_for_date_message, formattedDate))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.clear, (d, w) -> {
                            viewModel.deleteByDateRange(start, end);
                            showAnchoredSnackbar(Snackbar.make(binding.getRoot(), getString(R.string.snackbar_logs_deleted_date, formattedDate), Snackbar.LENGTH_SHORT));
                        }));
            };

            boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(proceedToDeleteDate, getString(R.string.auth_delete_notifications));
            } else {
                proceedToDeleteDate.run();
            }
        });

        picker.show(getParentFragmentManager(), "DELETE_CALENDAR_PICKER");
    }

    private void confirmDeleteAll() {
        Runnable proceedToClear = () -> {
            BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_all_title)
                    .setMessage(R.string.clear_all_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear, (dialog, which) -> {
                        viewModel.deleteAll();
                        showAnchoredSnackbar(Snackbar.make(binding.getRoot(), R.string.snackbar_all_notifications_cleared, Snackbar.LENGTH_SHORT));
                    }));
        };

        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("biometric_lock", false);
        if (isBiometricEnabled) {
            verifyBiometricsToProceed(proceedToClear, getString(R.string.auth_clear_all_notifications));
        } else {
            proceedToClear.run();
        }
    }

    private void showAnchoredSnackbar(Snackbar snackbar) {
        if (getActivity() != null) {
            View navCard = getActivity().findViewById(R.id.bottom_navigation_card);
            if (navCard != null) {
                snackbar.setAnchorView(navCard);
            }
        }
        snackbar.show();
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
                    Snackbar snackbar = Snackbar.make(binding.getRoot(), R.string.notification_deleted, Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo, v -> {
                                if (entity != null) {
                                    viewModel.insert(entity);
                                }
                            });
                    showAnchoredSnackbar(snackbar);
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
            if (binding != null) {
                binding.swipeRefresh.removeCallbacks(stopRefreshRunnable);
                binding.swipeRefresh.setRefreshing(false);
            }
            if (notifications == null || notifications.isEmpty()) {
                showRecyclerView(false);
            } else {
                showRecyclerView(true);
                
                if (isViewingMessage) {
                    isViewingMessage = false;
                    
                    androidx.recyclerview.widget.LinearLayoutManager layoutManager = 
                            (androidx.recyclerview.widget.LinearLayoutManager) binding.recyclerView.getLayoutManager();
                    int firstVisiblePos = -1;
                    int topOffset = 0;
                    if (layoutManager != null) {
                        firstVisiblePos = layoutManager.findFirstVisibleItemPosition();
                        android.view.View firstVisibleView = layoutManager.findViewByPosition(firstVisiblePos);
                        if (firstVisibleView != null) {
                            topOffset = firstVisibleView.getTop() - binding.recyclerView.getPaddingTop();
                        }
                    }

                    adapter.submitList(buildListWithHeaders(notifications));

                    if (layoutManager != null && firstVisiblePos >= 0) {
                        layoutManager.scrollToPositionWithOffset(firstVisiblePos, topOffset);
                    }
                } else {
                    adapter.submitList(buildListWithHeaders(notifications));
                }
            }
        });

        viewModel.getScrollToTopEvent().observe(getViewLifecycleOwner(), scroll -> {
            if (scroll != null && scroll) {
                closeSearchBox();
                binding.recyclerView.scrollToPosition(0);
                viewModel.clearScrollToTopEvent();
            }
        });

        viewModel.getSearchQuery().observe(getViewLifecycleOwner(), query -> {
            if (query == null || query.isEmpty()) {
                if (binding != null && binding.etSearch.getText() != null && binding.etSearch.getText().length() > 0) {
                    binding.etSearch.setText("");
                }
                if (searchBackPressedCallback != null) {
                    searchBackPressedCallback.setEnabled(false);
                }
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
                result.add(new NotificationAdapter.ListItem(DateUtils.getRelativeTimeLabel(getContext(), entity.timestamp)));
                lastGroup = group;
            }
            result.add(new NotificationAdapter.ListItem(entity));
        }
        return result;
    }

    private void showDetailDialog(NotificationEntity entity) {
        String decTitle = EncryptionHelper.decrypt(entity.title);
        String decText = EncryptionHelper.decrypt(entity.text);
        String decBigText = EncryptionHelper.decrypt(entity.bigText);

        String content = decBigText != null && !decBigText.isEmpty()
                ? decBigText : decText;

        android.widget.ScrollView scrollView = new android.widget.ScrollView(requireContext());
        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * requireContext().getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        android.widget.TextView textView = new android.widget.TextView(requireContext());
        textView.setText(content);
        textView.setTextSize(16);
        layout.addView(textView);

        if (entity.imagePath != null && !entity.imagePath.isEmpty()) {
            String[] imagePaths = entity.imagePath.split("\\|");
            int imgIndex = 1;
            for (String imgPath : imagePaths) {
                if (imgPath == null || imgPath.trim().isEmpty()) continue;
                final String currentPath = imgPath.trim();
                try {
                    java.io.File file = new java.io.File(currentPath);
                    byte[] decryptedBytes = EncryptionHelper.decryptFile(file);
                    if (decryptedBytes != null) {
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length);
                        if (bitmap != null) {
                            android.widget.ImageView imageView = new android.widget.ImageView(requireContext());
                            android.widget.LinearLayout.LayoutParams imgLp = new android.widget.LinearLayout.LayoutParams(
                                     android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    (int) (240 * requireContext().getResources().getDisplayMetrics().density)
                            );
                            imgLp.topMargin = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
                            imageView.setLayoutParams(imgLp);
                            imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                            imageView.setImageBitmap(bitmap);
                            layout.addView(imageView);

                            com.google.android.material.button.MaterialButton btnSave = new com.google.android.material.button.MaterialButton(requireContext());
                            android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            btnLp.topMargin = (int) (8 * requireContext().getResources().getDisplayMetrics().density);
                            btnLp.bottomMargin = (int) (12 * requireContext().getResources().getDisplayMetrics().density);
                            btnLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                            btnSave.setLayoutParams(btnLp);
                            btnSave.setText(R.string.save);
                            btnSave.setOnClickListener(v -> saveImageToPublicDirectory(currentPath, entity.appName));
                            layout.addView(btnSave);
                            imgIndex++;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        scrollView.addView(layout);

        BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                .setTitle(decTitle == null || decTitle.isEmpty() ? entity.appName : decTitle)
                .setView(scrollView)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.copy, (d, w) -> {
                    String text = (decTitle == null || decTitle.isEmpty() ? "" : decTitle + "\n") + content;
                    ClipboardManager clipboard = (ClipboardManager)
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("notification", text));
                    showAnchoredSnackbar(Snackbar.make(binding.getRoot(), R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT));
                }));
    }

    private void saveImageToPublicDirectory(String encImagePath, String appName) {
        try {
            java.io.File file = new java.io.File(encImagePath);
            byte[] decryptedBytes = EncryptionHelper.decryptFile(file);
            if (decryptedBytes == null) {
                android.widget.Toast.makeText(requireContext(), R.string.toast_decrypt_image_failed, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            String filename = "img_" + appName.replaceAll("\\s+", "_") + "_" + System.currentTimeMillis() + ".jpg";

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/notivault image record");
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);

                android.net.Uri collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                android.net.Uri imageUri = requireContext().getContentResolver().insert(collection, values);

                if (imageUri != null) {
                    try (java.io.OutputStream os = requireContext().getContentResolver().openOutputStream(imageUri)) {
                        os.write(decryptedBytes);
                    }
                    values.clear();
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                    requireContext().getContentResolver().update(imageUri, values, null, null);
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_image_saved, "Pictures/notivault image record"), android.widget.Toast.LENGTH_LONG).show();
                } else {
                    android.widget.Toast.makeText(requireContext(), R.string.toast_save_image_failed, android.widget.Toast.LENGTH_SHORT).show();
                }
            } else {
                java.io.File picturesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES);
                java.io.File targetDir = new java.io.File(picturesDir, "notivault image record");
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    android.widget.Toast.makeText(requireContext(), R.string.toast_create_dir_failed, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                java.io.File targetFile = new java.io.File(targetDir, filename);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                    fos.write(decryptedBytes);
                }
                android.media.MediaScannerConnection.scanFile(requireContext(),
                        new String[]{targetFile.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);
                android.widget.Toast.makeText(requireContext(), getString(R.string.toast_image_saved, targetFile.getAbsolutePath()), android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_error_saving_image, e.getMessage()), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void verifyBiometricsToProceed(Runnable onSuccess, String subtitle) {
        Executor executor = ContextCompat.getMainExecutor(requireContext());
        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(onSuccess);
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_identity_verification))
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    // ── Cloud Backup ──────────────────────────────────────────────────────────

    /**
     * Entry point: if a folder is already configured, go straight to the config
     * dialog; otherwise open the folder picker first.
     */
    private void showCloudBackupDialog() {
        String existingUri = PreferenceUtil.getCloudBackupUri(requireContext());
        if (existingUri != null) {
            showCloudBackupConfigDialog();
        } else {
            Context ctx = requireContext();

            // Build dialog view programmatically
            android.widget.ScrollView scrollView = new android.widget.ScrollView(ctx);
            android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
            container.setPadding(pad, pad / 2, pad, pad / 2);

            // Instructions text
            TextView tvInstruction = new TextView(ctx);
            tvInstruction.setText(R.string.cloud_backup_instruction);
            tvInstruction.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            tvInstruction.setPadding(0, 0, 0, (int) (12 * ctx.getResources().getDisplayMetrics().density));
            container.addView(tvInstruction);

            // Image guide
            android.widget.ImageView ivGuide = new android.widget.ImageView(ctx);
            ivGuide.setImageResource(R.drawable.drive_instruction);
            ivGuide.setAdjustViewBounds(true);
            android.widget.LinearLayout.LayoutParams imgLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            ivGuide.setLayoutParams(imgLp);
            container.addView(ivGuide);

            scrollView.addView(container);

            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.cloud_backup_setup_title)
                    .setView(scrollView)
                    .setPositiveButton(getString(R.string.pick_folder_countdown, 2), null)
                    .setNegativeButton(R.string.cancel, null)
                    .setCancelable(true)
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                android.widget.Button btnPositive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                btnPositive.setEnabled(false);
                btnPositive.setOnClickListener(v -> {
                    AppLockManager.setExpectingActivityResult(true);
                    folderPickerLauncher.launch(null);
                    dialog.dismiss();
                });

                // 2 seconds countdown handler
                final int[] count = {2};
                final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing() || !isAdded() || getActivity() == null) {
                            return;
                        }
                        if (count[0] > 0) {
                            btnPositive.setText(getString(R.string.pick_folder_countdown, count[0]));
                            count[0]--;
                            handler.postDelayed(this, 1000);
                        } else {
                            btnPositive.setText(R.string.pick_folder);
                            btnPositive.setEnabled(true);
                        }
                    }
                };
                handler.post(runnable);
            });

            BaseActivity.showDialog(ctx, dialog);
        }
    }

    /**
     * Main config dialog: shows current folder, password field, schedule
     * spinner, and buttons for manual backup + change folder.
     */
    private void showCloudBackupConfigDialog() {
        Context ctx = requireContext();
        String folderUri  = PreferenceUtil.getCloudBackupUri(ctx);
        String savedPass  = PreferenceUtil.getCloudBackupPassword(ctx);
        int    savedHours = PreferenceUtil.getCloudBackupIntervalHours(ctx);
        long   lastRun    = PreferenceUtil.getCloudBackupLastRun(ctx);

        // Build a short human-readable folder label from the URI
        String folderLabel = folderUri != null
                ? androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, Uri.parse(folderUri)) != null
                    ? "..." + folderUri.substring(Math.max(0, folderUri.lastIndexOf('%') - 0))
                          .replace("%2F", "/").replace("%3A", ":")
                    : folderUri
                : getString(R.string.label_none);
        // Trim for display
        if (folderLabel.length() > 45) folderLabel = "..." + folderLabel.substring(folderLabel.length() - 42);

        String lastRunLabel = lastRun == 0 ? getString(R.string.never) :
                new java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(lastRun));

        View dialogView = LayoutInflater.from(ctx).inflate(
                android.R.layout.select_dialog_item, null); // placeholder; we build programmatically

        // ── Build dialog content programmatically ────────────────────────────
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        // Folder info row
        TextView tvFolder = new TextView(ctx);
        tvFolder.setText(getString(R.string.label_folder_format, folderLabel));
        tvFolder.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        layout.addView(tvFolder);

        // Last run row
        TextView tvLast = new TextView(ctx);
        tvLast.setText(getString(R.string.label_last_backup_format, lastRunLabel));
        tvLast.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(4 * ctx.getResources().getDisplayMetrics().density);
        tvLast.setLayoutParams(lp);
        layout.addView(tvLast);

        // Divider
        View divider = new View(ctx);
        android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = pad / 2;
        divLp.bottomMargin = pad / 2;
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(0x1F888888);
        layout.addView(divider);

        // Password field
        com.google.android.material.textfield.TextInputLayout tilPass =
                new com.google.android.material.textfield.TextInputLayout(ctx);
        tilPass.setHint(getString(R.string.hint_backup_password));
        tilPass.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText etPass = new TextInputEditText(ctx);
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (savedPass != null) etPass.setText(savedPass);
        tilPass.addView(etPass);
        android.widget.LinearLayout.LayoutParams passLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tilPass.setLayoutParams(passLp);
        layout.addView(tilPass);

        // Schedule spinner
        TextView tvScheduleLabel = new TextView(ctx);
        tvScheduleLabel.setText(R.string.label_auto_backup_schedule);
        tvScheduleLabel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        android.widget.LinearLayout.LayoutParams sLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = (int)(12 * ctx.getResources().getDisplayMetrics().density);
        tvScheduleLabel.setLayoutParams(sLp);
        layout.addView(tvScheduleLabel);

        String[] scheduleLabels = getResources().getStringArray(R.array.cloud_backup_schedule_entries);
        int[]    scheduleHours  = {0, 24, 168, 720};
        Spinner spinner = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, scheduleLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // Select previously saved value
        for (int i = 0; i < scheduleHours.length; i++) {
            if (scheduleHours[i] == savedHours) { spinner.setSelection(i); break; }
        }
        layout.addView(spinner);

        // Include media toggle
        boolean savedIncludeMedia = PreferenceUtil.getCloudBackupIncludeMedia(ctx);
        com.google.android.material.materialswitch.MaterialSwitch switchMedia =
                new com.google.android.material.materialswitch.MaterialSwitch(ctx);
        switchMedia.setText(R.string.label_include_media_attachments);
        switchMedia.setChecked(savedIncludeMedia);
        android.widget.LinearLayout.LayoutParams mediaLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        mediaLp.topMargin = (int)(10 * ctx.getResources().getDisplayMetrics().density);
        mediaLp.bottomMargin = (int)(4 * ctx.getResources().getDisplayMetrics().density);
        switchMedia.setLayoutParams(mediaLp);
        layout.addView(switchMedia);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.cloud_backup_dialog_title)
                .setView(layout)
                .setPositiveButton(R.string.save_backup_now, null) // handled below
                .setNeutralButton(R.string.change_folder, (d, w) -> {
                    AppLockManager.setExpectingActivityResult(true);
                    folderPickerLauncher.launch(null);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String password = etPass.getText() != null ? etPass.getText().toString().trim() : "";
                if (password.isEmpty()) {
                    Toast.makeText(ctx, R.string.toast_password_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                int selectedIdx = spinner.getSelectedItemPosition();
                int hours = scheduleHours[selectedIdx];
                boolean includeMedia = switchMedia.isChecked();

                PreferenceUtil.setCloudBackupPassword(ctx, password);
                PreferenceUtil.setCloudBackupIntervalHours(ctx, hours);
                PreferenceUtil.setCloudBackupIncludeMedia(ctx, includeMedia);

                applySchedule(hours);
                runManualCloudBackup(includeMedia);
                dialog.dismiss();
            });
        });

        BaseActivity.showDialog(ctx, dialog);
    }

    private void applySchedule(int intervalHours) {
        WorkManager wm = WorkManager.getInstance(requireContext());
        if (intervalHours <= 0) {
            wm.cancelUniqueWork(CLOUD_BACKUP_WORK_TAG);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DriveBackupWorker.class, intervalHours, TimeUnit.HOURS)
                .addTag(CLOUD_BACKUP_WORK_TAG)
                .build();
        wm.enqueueUniquePeriodicWork(
                CLOUD_BACKUP_WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);
    }

    private void runManualCloudBackup(boolean includeMedia) {
        Context ctx = requireContext();
        String folderUriStr = PreferenceUtil.getCloudBackupUri(ctx);
        String password     = PreferenceUtil.getCloudBackupPassword(ctx);

        if (folderUriStr == null || password == null || password.isEmpty()) {
            Toast.makeText(ctx, R.string.toast_backup_folder_not_set, Toast.LENGTH_SHORT).show();
            return;
        }

        Uri folderUri = Uri.parse(folderUriStr);
        androidx.documentfile.provider.DocumentFile folder =
                androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, folderUri);

        if (folder == null || !folder.exists() || !folder.canWrite()) {
            Toast.makeText(ctx, R.string.toast_backup_folder_inaccessible, Toast.LENGTH_LONG).show();
            return;
        }

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String filename = "notivault_backup_" + timestamp + ".vault";

        androidx.documentfile.provider.DocumentFile newFile =
                folder.createFile("application/octet-stream", filename);
        if (newFile == null) {
            Toast.makeText(ctx, R.string.toast_backup_create_file_failed, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(ctx, com.zygisk_enc.notivault.service.BackupService.class);
        intent.setAction(com.zygisk_enc.notivault.service.BackupService.ACTION_EXPORT);
        intent.putExtra("uri", newFile.getUri().toString());
        intent.putExtra("password", password);
        intent.putExtra("includeMedia", includeMedia);
        intent.putExtra("isCloudBackup", true);
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent);

        Toast.makeText(ctx, R.string.toast_export_started, Toast.LENGTH_LONG).show();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.swipeRefresh.removeCallbacks(stopRefreshRunnable);
            binding.swipeRefresh.setRefreshing(false);
        }
        if (adapter != null && getContext() != null) {
            adapter.setShowReadUnreadStatus(PreferenceUtil.isShowReadUnreadEnabled(requireContext()));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (binding != null) {
            binding.swipeRefresh.removeCallbacks(stopRefreshRunnable);
            binding.swipeRefresh.setRefreshing(false);
        }
    }

    @Override
    public void onDestroyView() {
        if (searchDebounceRunnable != null) {
            searchDebounceHandler.removeCallbacks(searchDebounceRunnable);
        }
        if (binding != null) {
            binding.swipeRefresh.removeCallbacks(stopRefreshRunnable);
            binding.swipeRefresh.setRefreshing(false);
        }
        super.onDestroyView();
        binding = null;
    }
}
