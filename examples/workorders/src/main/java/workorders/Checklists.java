package workorders;

import java.util.List;

public final class Checklists {
    private Checklists() {
    }

    public static List<String> forEquipment(String equipment) {
        return switch (equipment == null ? "" : equipment) {
            case "Boiler" -> List.of(
                "Check the flue for blockages",
                "Measure the gas pressure",
                "Clean the burner and the heat exchanger",
                "Test the safety valve",
                "Analyse the combustion");
            case "Heat pump" -> List.of(
                "Clean the outdoor unit and its fins",
                "Check the refrigerant pressures",
                "Check the condensate drain",
                "Test the defrost cycle");
            case "Elevator" -> List.of(
                "Test the door sensors",
                "Inspect the cables and the sheaves",
                "Test the emergency phone",
                "Check the levelling at every floor",
                "Test the overspeed governor");
            case "Solar inverter" -> List.of(
                "Read the fault log",
                "Measure the string voltages",
                "Check the isolators and the wiring",
                "Confirm that the inverter feeds the grid");
            default -> List.of("Inspect the installation");
        };
    }
}
