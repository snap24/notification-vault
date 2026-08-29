import os
import re

translations = {
    'values-ar': {
        'nav_history': 'السجلات',
        'tap_to_edit_rule': 'اضغط للتعديل',
        'filter_by_app': 'تصفية حسب التطبيق',
        'app_filter_cleared': 'تمت إزالة تصفية التطبيقات',
        'app_filter_format': 'التطبيق: %s',
        'app_and_date_filter_format': 'التطبيق: %1$s  |  التاريخ: %2$s'
    },
    'values-de': {
        'nav_history': 'Protokolle',
        'tap_to_edit_rule': 'Tippen zum Bearbeiten',
        'filter_by_app': 'Nach App filtern',
        'app_filter_cleared': 'App-Filter gelöscht',
        'app_filter_format': 'App: %s',
        'app_and_date_filter_format': 'App: %1$s  |  Datum: %2$s'
    },
    'values-es': {
        'nav_history': 'Registros',
        'tap_to_edit_rule': 'Toca para editar',
        'filter_by_app': 'Filtrar por aplicación',
        'app_filter_cleared': 'Filtro de aplicaciones borrado',
        'app_filter_format': 'App: %s',
        'app_and_date_filter_format': 'App: %1$s  |  Fecha: %2$s'
    },
    'values-fr': {
        'nav_history': 'Journaux',
        'tap_to_edit_rule': 'Appuyer pour modifier',
        'filter_by_app': 'Filtrer par application',
        'app_filter_cleared': "Filtre d\\'application effacé",
        'app_filter_format': 'App : %s',
        'app_and_date_filter_format': 'App : %1$s  |  Date : %2$s'
    },
    'values-id': {
        'nav_history': 'Log',
        'tap_to_edit_rule': 'Ketuk untuk mengedit',
        'filter_by_app': 'Filter berdasarkan aplikasi',
        'app_filter_cleared': 'Filter aplikasi dihapus',
        'app_filter_format': 'Aplikasi: %s',
        'app_and_date_filter_format': 'Aplikasi: %1$s  |  Tanggal: %2$s'
    },
    'values-in': {
        'nav_history': 'Log',
        'tap_to_edit_rule': 'Ketuk untuk mengedit',
        'filter_by_app': 'Filter berdasarkan aplikasi',
        'app_filter_cleared': 'Filter aplikasi dihapus',
        'app_filter_format': 'Aplikasi: %s',
        'app_and_date_filter_format': 'Aplikasi: %1$s  |  Tanggal: %2$s'
    },
    'values-it': {
        'nav_history': 'Registri',
        'tap_to_edit_rule': 'Tocca per modificare',
        'filter_by_app': 'Filtra per applicazione',
        'app_filter_cleared': 'Filtro applicazione rimosso',
        'app_filter_format': 'App: %s',
        'app_and_date_filter_format': 'App: %1$s  |  Data: %2$s'
    },
    'values-ja': {
        'nav_history': 'ログ',
        'tap_to_edit_rule': 'タップして編集',
        'filter_by_app': 'アプリで絞り込み',
        'app_filter_cleared': 'アプリの絞り込みを解除しました',
        'app_filter_format': 'アプリ: %s',
        'app_and_date_filter_format': 'アプリ: %1$s  |  日付: %2$s'
    },
    'values-ko': {
        'nav_history': '로그',
        'tap_to_edit_rule': '수정하려면 탭',
        'filter_by_app': '앱별 필터링',
        'app_filter_cleared': '앱 필터 초기화됨',
        'app_filter_format': '앱: %s',
        'app_and_date_filter_format': '앱: %1$s  |  날짜: %2$s'
    },
    'values-pl': {
        'nav_history': 'Dzienniki',
        'tap_to_edit_rule': 'Dotknij, aby edytować',
        'filter_by_app': 'Filtruj według aplikacji',
        'app_filter_cleared': 'Filtr aplikacji wyczyszczony',
        'app_filter_format': 'Aplikacja: %s',
        'app_and_date_filter_format': 'Aplikacja: %1$s  |  Data: %2$s'
    },
    'values-pt-rBR': {
        'nav_history': 'Registros',
        'tap_to_edit_rule': 'Toque para editar',
        'filter_by_app': 'Filtrar por aplicativo',
        'app_filter_cleared': 'Filtro de aplicativo limpo',
        'app_filter_format': 'App: %s',
        'app_and_date_filter_format': 'App: %1$s  |  Data: %2$s'
    },
    'values-ru': {
        'nav_history': 'Журналы',
        'tap_to_edit_rule': 'Нажмите для ред.',
        'filter_by_app': 'Фильтр по приложению',
        'app_filter_cleared': 'Фильтр приложений очищен',
        'app_filter_format': 'Приложение: %s',
        'app_and_date_filter_format': 'Приложение: %1$s  |  Дата: %2$s'
    },
    'values-tr': {
        'nav_history': 'Günlükler',
        'tap_to_edit_rule': 'Düzenlemek için dokunun',
        'filter_by_app': 'Uygulamaya göre filtrele',
        'app_filter_cleared': 'Uygulama filtresi temizlendi',
        'app_filter_format': 'Uygulama: %s',
        'app_and_date_filter_format': 'Uygulama: %1$s  |  Tarih: %2$s'
    },
    'values-zh-rCN': {
        'nav_history': '日志',
        'tap_to_edit_rule': '点击编辑',
        'filter_by_app': '按应用筛选',
        'app_filter_cleared': '已清除应用筛选',
        'app_filter_format': '应用: %s',
        'app_and_date_filter_format': '应用: %1$s  |  日期: %2$s'
    }
}

for folder, t_dict in translations.items():
    file_path = os.path.join('app/src/main/res', folder, 'strings.xml')
    if not os.path.exists(file_path):
        continue
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Remove any broken new entries previously appended
    for k in ['tap_to_edit_rule', 'filter_by_app', 'app_filter_cleared', 'app_filter_format', 'app_and_date_filter_format', 'app_and_date_format']:
        content = re.sub(rf'\s*<string name="{k}">.*?</string>', '', content)

    # Update nav_history
    if 'nav_history' in t_dict:
        content = re.sub(r'<string name="nav_history">.*?</string>', f'<string name="nav_history">{t_dict["nav_history"]}</string>', content)

    # Re-append cleanly
    new_strings = []
    for key in ['tap_to_edit_rule', 'filter_by_app', 'app_filter_cleared', 'app_filter_format', 'app_and_date_filter_format']:
        val = t_dict[key]
        new_strings.append(f'    <string name="{key}">{val}</string>')

    insert_text = '\n' + '\n'.join(new_strings) + '\n</resources>'
    content = re.sub(r'</resources>', insert_text, content)

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'Fixed {file_path}')
