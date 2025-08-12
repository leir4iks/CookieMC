package io.cookiemc.cookie.util.entity;

public interface EquipmentInfo {

    boolean lithium$shouldTickEnchantments();

    boolean lithium$hasUnsentEquipmentChanges();

    void lithium$onEquipmentChangesSent();
}
