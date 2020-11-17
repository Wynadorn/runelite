/*
 * Copyright (c) 2019, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.music;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("music")
public interface MusicConfig extends Config
{
	@ConfigSection(
		name = "Custom Filters",
		description = "Create custom sound effect filters",
		position = 99
	)
	String customFiltersSection = "customFilters";

	@ConfigItem(
		keyName = "enableCustomFilters",
		name = "Enable Custom Filters",
		description = "Configures whether the custom filter is active",
		position = 1,
		section = customFiltersSection
	)
	default boolean customFiltersEnabled()
	{
		return true;
	}

	@ConfigItem(
		position = 2,
		keyName = "listSoundsMenu",
		name = "List Sounds",
		description = "List recently played sounds",
		section = customFiltersSection
	)
	default boolean listSoundEffects()
	{
		return true;
	}

	@ConfigItem(
		position = 3,
		keyName = "soundsToFilter",
		name = "Sounds to filter",
		description = "List of sound effects to filter",
		section = customFiltersSection
	)
	default String getCustomFilteredSounds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "soundsToFilter",
		name = "",
		description = "",
		section = customFiltersSection
	)
	void setCustomFilteredSounds(String soundsToFilter);




	@ConfigItem(
		keyName = "muteOwnAreaSounds",
		name = "Mute player area sounds",
		description = "Mute area sounds caused by yourself",
		position = 0
	)
	default boolean muteOwnAreaSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "muteOtherAreaSounds",
		name = "Mute other players' area sounds",
		description = "Mute area sounds caused by other players",
		position = 1
	)
	default boolean muteOtherAreaSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "muteOtherAreaNPCSounds",
		name = "Mute NPCs' area sounds",
		description = "Mute area sounds caused by NPCs",
		position = 2
	)
	default boolean muteNpcAreaSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "muteOtherAreaEnvironmentSounds",
		name = "Mute environment area sounds",
		description = "Mute area sounds caused by neither NPCs nor players",
		position = 3
	)
	default boolean muteEnvironmentAreaSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "mutePrayerSounds",
		name = "Mute prayer sounds",
		description = "Mute prayer activation and deactivation sounds",
		position = 4
	)
	default boolean mutePrayerSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "musicVolume",
		name = "",
		description = "",
		hidden = true
	)
	default int getMusicVolume()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "musicVolume",
		name = "",
		description = "",
		hidden = true
	)
	void setMusicVolume(int vol);

	@ConfigItem(
		keyName = "soundEffectVolume",
		name = "",
		description = "",
		hidden = true
	)
	default int getSoundEffectVolume()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "soundEffectVolume",
		name = "",
		description = "",
		hidden = true
	)
	void setSoundEffectVolume(int val);

	@ConfigItem(
		keyName = "areaSoundEffectVolume",
		name = "",
		description = "",
		hidden = true
	)
	default int getAreaSoundEffectVolume()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "areaSoundEffectVolume",
		name = "",
		description = "",
		hidden = true
	)
	void setAreaSoundEffectVolume(int vol);
}
