@tool
extends EditorPlugin

# Android export for phones running the HyperMagic OpenXR runtime (any OpenXR runtime found
# through the Khronos broker): the Khronos loader instead of Meta's, and the category OpenXR
# launchers look for.

class HyperMagicExport extends EditorExportPlugin:
	func _get_name() -> String:
		return "HyperMagicExport"

	func _supports_platform(platform: EditorExportPlatform) -> bool:
		return platform is EditorExportPlatformAndroid

	func _get_android_dependencies(platform: EditorExportPlatform, debug: bool) -> PackedStringArray:
		return PackedStringArray(["org.khronos.openxr:openxr_loader_for_android:1.1.49"])

	func _get_android_manifest_activity_element_contents(platform: EditorExportPlatform, debug: bool) -> String:
		return """
				<intent-filter>
					<action android:name="android.intent.action.MAIN" />
					<category android:name="org.khronos.openxr.intent.category.IMMERSIVE_HMD" />
				</intent-filter>
"""

var _export: HyperMagicExport

func _enter_tree() -> void:
	_export = HyperMagicExport.new()
	add_export_plugin(_export)

func _exit_tree() -> void:
	remove_export_plugin(_export)
	_export = null
