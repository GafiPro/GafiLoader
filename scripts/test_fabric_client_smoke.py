import unittest

from run_fabric_client_smoke import has_legacy_mod_startup_milestone


class LegacyModStartupMilestoneTest(unittest.TestCase):
    def test_resource_reload_start_is_not_a_success(self):
        log_text = """
        [main/INFO]: Loading 2 mods: fabric-api, smallhands
        [Render thread/INFO]: Reloading ResourceManager: vanilla
        """
        self.assertFalse(has_legacy_mod_startup_milestone(log_text, "smallhands"))

    def test_sound_engine_without_texture_atlas_is_not_a_success(self):
        log_text = """
        [main/INFO]: Loading 2 mods: fabric-api, smallhands
        [Render thread/INFO]: Sound engine started
        """
        self.assertFalse(has_legacy_mod_startup_milestone(log_text, "smallhands"))

    def test_completed_sound_and_texture_startup_with_legacy_mod_passes(self):
        log_text = """
        [main/INFO]: Loading 2 mods: fabric-api, smallhands
        [Render thread/INFO]: Sound engine started
        [Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas
        """
        self.assertTrue(has_legacy_mod_startup_milestone(log_text, "smallhands"))

    def test_other_mod_does_not_satisfy_selected_legacy_mod_check(self):
        log_text = """
        [main/INFO]: Loading 2 mods: fabric-api, modernmod
        [Render thread/INFO]: Sound engine started
        [Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas
        """
        self.assertFalse(has_legacy_mod_startup_milestone(log_text, "smallhands"))


if __name__ == "__main__":
    unittest.main()
