package dev.replenishplusplus.pad;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PadIcons {

    public record Icon(String id, Material material, String title) {}

    private static final List<Icon> ICONS = build();

    private PadIcons() {}

    public static List<Icon> all() {
        return ICONS;
    }

    public static Icon byId(String id) {
        for (Icon icon : ICONS) {
            if (icon.id().equals(id)) return icon;
        }
        return ICONS.get(0);
    }

    public static Icon defaultIcon() {
        return ICONS.get(0);
    }

    private static List<Icon> build() {
        Material[] wools = {
                Material.RED_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.BLUE_WOOL,
                Material.MAGENTA_WOOL, Material.ORANGE_WOOL, Material.GRAY_WOOL, Material.WHITE_WOOL,
                Material.GREEN_WOOL, Material.LIGHT_BLUE_WOOL, Material.PURPLE_WOOL, Material.BLACK_WOOL,
                Material.CYAN_WOOL};
        String[] colorIds = {"RED", "YELLOW", "LIME", "BLUE", "MAGENTA", "ORANGE", "GRAY", "WHITE",
                "GREEN", "AQUA", "PURPLE", "BLACK", "CYAN"};
        String[] colorTitles = {"Red", "Yellow", "Lime", "Blue", "Magenta", "Orange", "Gray", "White",
                "Green", "Aqua", "Purple", "Black", "Cyan"};

        String[] numbers = {"One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
                "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
                "Eighteen", "Nineteen", "Twenty", "TwentyOne", "TwentyTwo", "TwentyThree", "TwentyFour",
                "TwentyFive", "TwentySix", "TwentySeven", "TwentyEight", "TwentyNine", "Thirty",
                "ThirtyOne", "ThirtyTwo", "ThirtyThree", "ThirtyFour", "ThirtyFive", "ThirtySix",
                "ThirtySeven", "ThirtyEight", "ThirtyNine", "Forty", "FortyOne", "FortyTwo", "FortyThree"};

        List<Icon> icons = new ArrayList<>();
        for (int i = 0; i < wools.length; i++) {
            icons.add(new Icon(colorIds[i], wools[i], colorTitles[i]));
        }
        for (String number : numbers) {
            icons.add(new Icon(number.toUpperCase(Locale.ROOT), Material.PAPER, number));
        }
        return List.copyOf(icons);
    }
}
