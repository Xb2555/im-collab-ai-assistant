use tauri_plugin_deep_link::DeepLinkExt;
use tauri::{Manager, Emitter}; // ✨ 必须引入 Emitter 才能发事件

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .plugin(tauri_plugin_deep_link::init())
    .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
        // 1. 把原来的窗口拽到最前面
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.unminimize();
            let _ = window.set_focus();
        }
        
        // ✨ 2. 核心魔法：翻找新窗口带来的参数，找到 agentpilot 链接，通过事件发给前端！
        for arg in args {
            if arg.starts_with("agentpilot://") {
                // 向前端广播一个叫 "oauth-deep-link" 的自定义事件
                let _ = app.emit("oauth-deep-link", arg);
                break;
            }
        }
    }))
    .setup(|app| {
      #[cfg(any(windows, target_os = "linux"))]
      let _ = app.deep_link().register("agentpilot");

      if cfg!(debug_assertions) {
        app.handle().plugin(
          tauri_plugin_log::Builder::default()
            .level(log::LevelFilter::Info)
            .build(),
        )?;
      }
      Ok(())
    })
    .run(tauri::generate_context!())
    .expect("error while running tauri application");
}