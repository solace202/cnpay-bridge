package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cnpay.money.Money;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesYuanStringsIntoFen() {
        assertThat(Money.parseYuan("88")).isEqualTo(8800);
        assertThat(Money.parseYuan("88.8")).isEqualTo(8880);
        assertThat(Money.parseYuan("0.01")).isEqualTo(1);
        assertThat(Money.parseYuan(" 1999.90 ")).isEqualTo(199990);
        assertThat(Money.parseYuan("12.300")).isEqualTo(1230);
    }

    @Test
    void rejectsSubFenZeroNegativeAndGarbage() {
        assertThatThrownBy(() -> Money.parseYuan("0.001")).hasMessageContaining("two decimal");
        assertThatThrownBy(() -> Money.parseYuan("0")).hasMessageContaining("at least");
        assertThatThrownBy(() -> Money.parseYuan("-5.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parseYuan("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parseYuan("100000000.01")).hasMessageContaining("limit");
    }

    @Test
    void formatsFenAsTwoDecimalYuan() {
        assertThat(Money.toYuan(8800)).isEqualTo("88.00");
        assertThat(Money.toYuan(1)).isEqualTo("0.01");
        assertThat(Money.display(123456)).isEqualTo("¥1234.56");
    }

    @Test
    void parsesSignedBillAmounts() {
        assertThat(Money.parseBillYuan("-20.00")).isEqualTo(-2000);
        assertThat(Money.parseBillYuan("1,234.50")).isEqualTo(123450);
        assertThat(Money.parseBillYuan("")).isZero();
    }
}
