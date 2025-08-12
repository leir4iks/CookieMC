package io.canvasmc.canvas.util.entity;

public interface EquipmentInfo {

    boolean lithium$shouldTickEnchantments();

    boolean lithium$hasUnsentEquipmentChanges();

    void lithium$onEquipmentChangesSent();
}
