package com.eugen.playthenews;
final class Labels {
    static String category(String name) {
        if (name == null) return "";
        switch (name) {
            case "General": return "General";
            case "World": return "Món";
            case "Business": return "Economia";
            case "Technology": return "Tecnologia";
            case "Science": return "Ciència";
            case "Health": return "Salut";
            case "Sports": return "Esports";
            case "Entertainment": return "Cultura";
            case "Politics": return "Política";
            case "Local": return "Local";
            case "National": return "Nacional";
            default: return name;
        }
    }
}
