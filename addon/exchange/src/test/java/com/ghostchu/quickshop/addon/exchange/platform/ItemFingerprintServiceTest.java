package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.shop.ItemMatcher;
import com.ghostchu.quickshop.platform.Platform;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemFingerprintServiceTest {
  @Test
  void strictFingerprintIgnoresAmountAndTransferMarkerOnly() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack one = new ItemStack(Material.DIAMOND, 1);
    ItemStack sixtyFour = one.clone();
    sixtyFour.setAmount(64);
    fixture.mark(sixtyFour, UUID.randomUUID());
    ItemStack named = one.clone();
    named.editMeta(meta -> meta.setDisplayName("Special"));

    assertThat(fixture.service().fingerprint(one, FingerprintMode.STRICT))
        .isEqualTo(fixture.service().fingerprint(sixtyFour, FingerprintMode.STRICT));
    assertThat(fixture.service().fingerprint(named, FingerprintMode.STRICT))
        .isNotEqualTo(fixture.service().fingerprint(one, FingerprintMode.STRICT));
  }

  @Test
  void vanillaMaterialMarketRejectsMetadata() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack named = new ItemStack(Material.DIAMOND);
    named.editMeta(meta -> meta.setDisplayName("Special"));

    assertThat(fixture.service().acceptsVanillaMaterial(named, Material.DIAMOND)).isFalse();
  }

  private record ItemFingerprintFixture(ItemFingerprintService service, NamespacedKey marker) {
    static ItemFingerprintFixture create() {
      Platform platform = proxy(Platform.class, (ignored, method, arguments) -> {
        if (method.getName().equals("encodeStack")) {
          return Base64.getEncoder().encodeToString(((ItemStack) arguments[0]).serializeAsBytes());
        }
        return defaultValue(method.getReturnType());
      });
      ItemMatcher matcher = proxy(ItemMatcher.class, (ignored, method, arguments) -> {
        if (method.getName().equals("matches")) {
          return ((ItemStack) arguments[0]).isSimilar((ItemStack) arguments[1]);
        }
        return defaultValue(method.getReturnType());
      });
      NamespacedKey marker = new NamespacedKey("exchange", "transfer");
      return new ItemFingerprintFixture(
          new ItemFingerprintService(quickShop(platform, matcher), marker), marker);
    }

    void mark(ItemStack stack, UUID transferId) {
      stack.editMeta(meta -> meta.getPersistentDataContainer()
          .set(marker, PersistentDataType.STRING, transferId.toString()));
    }
  }

  private static final class TestQuickShop extends QuickShop {
    private final Platform platform;
    private final ItemMatcher matcher;

    private TestQuickShop() {
      super(null, null, null);
      this.platform = null;
      this.matcher = null;
    }

    @Override
    public Platform platform() {
      return platform;
    }

    @Override
    public ItemMatcher getItemMatcher() {
      return matcher;
    }
  }

  private static QuickShop quickShop(Platform platform, ItemMatcher matcher) {
    try {
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      TestQuickShop quickShop = (TestQuickShop) unsafe.allocateInstance(TestQuickShop.class);
      Field platformField = TestQuickShop.class.getDeclaredField("platform");
      platformField.setAccessible(true);
      platformField.set(quickShop, platform);
      Field matcherField = TestQuickShop.class.getDeclaredField("matcher");
      matcherField.setAccessible(true);
      matcherField.set(quickShop, matcher);
      return quickShop;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == double.class) return 0D;
    if (type == float.class) return 0F;
    if (type == short.class) return (short) 0;
    if (type == byte.class) return (byte) 0;
    if (type == char.class) return (char) 0;
    return null;
  }
}
