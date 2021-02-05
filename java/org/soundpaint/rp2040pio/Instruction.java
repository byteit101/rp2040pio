/*
 * @(#)Instruction.java 1.00 21/01/31
 *
 * Copyright (C) 2021 Jürgen Reuter
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 * For updates and more info or contacting the author, visit:
 * <https://github.com/soundpaint/rp2040pio>
 *
 * Author's web site: www.juergen-reuter.de
 */
package org.soundpaint.rp2040pio;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Instruction
 */
public abstract class Instruction
{
  protected final SM sm;
  private int delay;
  private int sideSet;

  public enum ResultState
  {
    /**
     * The instruction leaves it to the program control to ordinarily
     * update the program counter (PC) by increasing it by one and
     * perform any delay.
     */
    COMPLETE,
    /**
     * The PC must be kept unmodified (e.g. as the result of a wait
     * instruction keeping unfulfilled or an additional synthetic
     * instruction being inserted), and any delay must not yet be
     * performed.
     */
    STALL,
    /**
     * The instruction has modified the PC by itself.  Delay must be
     * ordinarily performed.
     */
    JUMP
  };

  private Instruction()
  {
    throw new UnsupportedOperationException("unsupported empty constructor");
  }

  public Instruction(final SM sm)
  {
    this.sm = sm;
    delay = 0;
  }

  abstract ResultState execute();

  private short[] DELAY_MASK = {
    0x1f, 0x0f, 0x07, 0x03, 0x01, 0x00
  };

  public int getDelay()
  {
    return delay;
  }

  private String getDelayDisplayValue()
  {
    return delay > 0 ? "[" + delay + "]" : "";
  }

  public int getSideSet()
  {
    return sideSet;
  }

  private String getSideSetDisplayValue()
  {
    return sideSet >= 0 ? "side " + Integer.toString(sideSet) : "";
  }

  public Instruction decode(final short word)
    throws Decoder.DecodeException
  {
    final SM.Status smStatus = sm.getStatus();
    final int delayAndSideSet = (word >>> 0x8) & 0x1f;
    final int delayMask = DELAY_MASK[smStatus.sideSetCount];
    delay = delayAndSideSet & delayMask;
    final int enableRemoved =
      smStatus.sideSetEnable ? delayAndSideSet & 0xf : delayAndSideSet;
    if (smStatus.sideSetCount >= 1) {
      sideSet = enableRemoved >>> (5 - smStatus.sideSetCount);
    } else {
      sideSet = -1;
    }
    decodeLSB(word & 0xff);
    return this;
  }

  /**
   * Updates all internal data of this instruction according to the
   * argument bits of the instruction word.
   */
  abstract void decodeLSB(final int lsb) throws Decoder.DecodeException;

  abstract String getMnemonic();

  abstract String getParamsDisplay();

  protected static void checkIRQIndex(final int irqIndex)
    throws Decoder.DecodeException
  {
    if ((irqIndex & 0x08) != 0) {
      throw new Decoder.DecodeException();
    }
    if (((irqIndex & 0x10) != 0) &&
        ((irqIndex & 0x04) != 0)) {
      throw new Decoder.DecodeException();
    }
  }

  protected static int getIRQNum(final int smNum, final int index)
  {
    return
      (index & 0x10) != 0 ? (smNum + index) & 0x3 : index & 0x7;
  }

  protected static String getIRQNumDisplay(final int index)
  {
    return
      (index & 0x10) != 0 ?
      (index & 0x3) + "_rel" :
      Integer.toString(index & 0x7);
  }

  @Override
  public String toString()
  {
    final String mnemonic = getMnemonic();
    final String paramsDisplay = getParamsDisplay();
    final String sideSetDisplayValue = getSideSetDisplayValue();
    final String delayDisplayValue = getDelayDisplayValue();
    return
      String.format("%-16s%s",
                    mnemonic +
                    (!paramsDisplay.isEmpty() ? " " + paramsDisplay : ""),
                    sideSetDisplayValue +
                    (!sideSetDisplayValue.isEmpty() &&
                     !delayDisplayValue.isEmpty() ? " " : "") +
                    delayDisplayValue);
  }

  public static class Jmp extends Instruction
  {
    private static final Map<Integer, Condition> code2cond =
      new HashMap<Integer, Condition>();

    private enum Condition
    {
      ALWAYS(0b000, "", (smStatus) -> true),
      NOT_X(0b001, "!x", (smStatus) -> smStatus.regX == 0),
      DEC_X(0b010, "x--", (smStatus) -> smStatus.regX-- == 0),
      NOT_Y(0b011, "!y", (smStatus) -> smStatus.regY == 0),
      DEC_Y(0b100, "y--", (smStatus) -> smStatus.regY-- == 0),
      X_NEQ_Y(0b101, "x!=y", (smStatus) -> smStatus.regX != smStatus.regX),
      PIN(0b110, "pin", (smStatus) -> smStatus.jmpPin() == GPIO.Bit.HIGH),
      NOT_OSRE(0b111, "!osre", (smStatus) -> !smStatus.osrEmpty());

      private final int code;
      private final String mnemonic;
      private final Function<SM.Status, Boolean> eval;

      private Condition(final int code, final String mnemonic,
                        final Function<SM.Status, Boolean> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2cond.put(code, this);
      }

      public boolean fulfilled(final SM.Status smStatus)
      {
        return eval.apply(smStatus);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private int address;
    private Condition condition;

    public Jmp(final SM sm)
    {
      super(sm);

      // force class initializer to be called such that map is filled
      condition = Condition.ALWAYS;
    }

    @Override
    public ResultState execute()
    {
      final SM.Status smStatus = sm.getStatus();
      final boolean doJump = condition.fulfilled(smStatus);
      if (doJump) smStatus.regPC = address;
      return doJump ? ResultState.JUMP : ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
    {
      address = lsb & 0x1f;
      condition = code2cond.get((lsb >>> 5) & 0x3);
    }

    @Override
    public String getMnemonic()
    {
      return "jmp";
    }

    @Override
    public String getParamsDisplay()
    {
      final String conditionDisplay = condition.toString();
      return
        (!conditionDisplay.isEmpty() ? conditionDisplay + ", " : "") +
        address;
    }
  }

  public static class Wait extends Instruction
  {
    private static final Map<Integer, Source> code2src =
      new HashMap<Integer, Source>();

    private enum Source
    {
      GPIO_(0b00, "gpio", (wait) -> wait.sm.getGPIO(wait.index)),
      PIN(0b01, "pin", (wait) -> wait.sm.getPin(wait.index)),
      IRQ(0b10, "irq", (wait) -> {
          final int irqNum = getIRQNum(wait.sm.getNum(), wait.index);
          final GPIO.Bit bit = wait.sm.getIRQ(irqNum);
          if ((wait.polarity == GPIO.Bit.HIGH) && (bit == wait.polarity))
            wait.sm.clearIRQ(irqNum);
          return bit;
        }),
      RESERVED_3(0b11, "???", null);

      private final int code;
      private final String mnemonic;
      private final Function<Wait, GPIO.Bit> eval;

      private Source(final int code, final String mnemonic,
                     final Function<Wait, GPIO.Bit> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2src.put(code, this);
      }

      public GPIO.Bit getBit(final Wait wait)
      {
        return eval.apply(wait);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private GPIO.Bit polarity;
    private Source src;
    private int index;

    public Wait(final SM sm)
    {
      super(sm);
      polarity = GPIO.Bit.LOW;

      // force class initializer to be called such that map is filled
      src = Source.GPIO_;
    }

    @Override
    public ResultState execute()
    {
      final boolean doStall = src.getBit(this) != polarity;
      return doStall ? ResultState.STALL : ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      polarity = (lsb & 0x80) != 0 ? GPIO.Bit.HIGH : GPIO.Bit.LOW;
      src = code2src.get((lsb & 0x7f) >>> 5);
      if (src == Source.RESERVED_3) {
        throw new Decoder.DecodeException();
      }
      index = lsb & 0x1f;
      checkIRQIndex(index);
    }

    @Override
    public String getMnemonic()
    {
      return "wait";
    }

    @Override
    public String getParamsDisplay()
    {
      final int maskedIndex;
      final String num =
        src == Source.IRQ ? getIRQNumDisplay(index) : Integer.toString(index);
      return polarity + " " + src + " " + num;
    }
  }

  public static class In extends Instruction
  {
    private static final Map<Integer, Source> code2src =
      new HashMap<Integer, Source>();

    private enum Source
    {
      PINS(0b000, "pins", (sm) -> sm.getAllPins()),
      X(0b001, "x", (sm) -> sm.getX()),
      Y(0b010, "y", (sm) -> sm.getX()),
      NULL(0b011, "null", (sm) -> 0),
      RESERVED_4(0b100, "???", null),
      RESERVED_5(0b101, "???", null),
      ISR(0b110, "ISR", (sm) -> sm.getISRValue()),
      OSR(0b111, "OSR", (sm) -> sm.getOSRValue());

      private final int code;
      private final String mnemonic;
      private final Function<SM, Integer> eval;

      private Source(final int code, final String mnemonic,
                     final Function<SM, Integer> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2src.put(code, this);
      }

      public Integer getData(final SM sm)
      {
        return eval.apply(sm);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private Source src;
    private int bitCount;

    public In(final SM sm)
    {
      super(sm);

      // force class initializer to be called such that map is filled
      src = Source.PINS;
    }

    @Override
    public ResultState execute()
    {
      final boolean stall;
      if (sm.getInShiftDir() == PIO.ShiftDir.SHIFT_LEFT) {
        stall = sm.shiftISRLeft(bitCount, src.getData(sm));
      } else {
        stall = sm.shiftISRRight(bitCount, src.getData(sm));
      }
      return stall ? ResultState.STALL : ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      src = code2src.get((lsb & 0xe0) >>> 5);
      if ((src == Source.RESERVED_4) ||
          (src == Source.RESERVED_5)) {
        throw new Decoder.DecodeException();
      }
      bitCount = lsb & 0x1f;
      if (bitCount == 0) bitCount = 32;
    }

    @Override
    public String getMnemonic()
    {
      return "in";
    }

    @Override
    public String getParamsDisplay()
    {
      return src + ", " + bitCount;
    }
  }

  public static class Out extends Instruction
  {
    private static final Map<Integer, Destination> code2dst =
      new HashMap<Integer, Destination>();

    private enum Destination
    {
      PINS(0b000, "pins", (sm, data) -> {
          sm.setAllPins(data);
          return null;
        }),
      X(0b001, "x", (sm, data) -> {
          sm.setX(data);
          return null;
        }),
      Y(0b010, "y", (sm, data) -> {
          sm.setY(data);
          return null;
        }),
      NULL(0b011, "null", (sm, data) -> {
          return null;
        }),
      PINDIRS(0b100, "pindirs", (sm, data) -> {
          sm.setPinDirs(data);
          return null;
        }),
      PC(0b101, "pc", (sm, data) -> {
          sm.setPC(data);
          return null;
        }),
      ISR(0b110, "isr", (sm, data) -> {
          sm.setISRValue(data);
          return null;
        }),
      EXEC(0b111, "exec", (sm, data) -> {
          sm.insertInstruction(data);
          return null;
        });

      private final int code;
      private final String mnemonic;
      private final BiFunction<SM, Integer, Void> eval;

      private Destination(final int code, final String mnemonic,
                          final BiFunction<SM, Integer, Void> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2dst.put(code, this);
      }

      public IntConsumer getConsumer(final SM sm)
      {
        return (data) -> {
          eval.apply(sm, data);
        };
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private Destination dst;
    private int bitCount;

    public Out(final SM sm)
    {
      super(sm);

      // force class initializer to be called such that map is filled
      dst = Destination.PINS;
    }

    @Override
    public ResultState execute()
    {
      final boolean stall;
      if (sm.getOutShiftDir() == PIO.ShiftDir.SHIFT_LEFT) {
        stall = sm.shiftOSRLeft(bitCount, dst.getConsumer(sm));
      } else {
        stall = sm.shiftOSRRight(bitCount, dst.getConsumer(sm));
      }
      return (stall || dst == Destination.EXEC) ?
        ResultState.STALL :
        (dst == Destination.PC ?
         ResultState.JUMP :
         ResultState.COMPLETE);
    }

    @Override
    public void decodeLSB(final int lsb)
    {
      dst = code2dst.get((lsb & 0xe0) >>> 5);
      bitCount = lsb & 0x1f;
      if (bitCount == 0) bitCount = 32;
    }

    @Override
    public String getMnemonic()
    {
      return "out";
    }

    @Override
    public String getParamsDisplay()
    {
      return dst + ", " + bitCount;
    }
  }

  public static class Push extends Instruction
  {
    public Push(final SM sm)
    {
      super(sm);
    }

    private boolean ifFull;
    private boolean block;

    @Override
    public ResultState execute()
    {
      return sm.rxPush(ifFull, block) ?
        ResultState.STALL :
        ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      ifFull = (lsb & 0x40) != 0;
      block = (lsb & 0x20) != 0;
      if ((lsb & 0x1f) != 0) {
        throw new Decoder.DecodeException();
      }
    }

    @Override
    public String getMnemonic()
    {
      return "push";
    }

    @Override
    public String getParamsDisplay()
    {
      return (ifFull ? "iffull " : "") + (block ? "block" : "noblock");
    }
  }

  public static class Pull extends Instruction
  {
    public Pull(final SM sm)
    {
      super(sm);
    }

    private boolean ifEmpty;
    private boolean block;

    @Override
    public ResultState execute()
    {
      return sm.txPull(ifEmpty, block) ?
        ResultState.STALL :
        ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      ifEmpty = (lsb & 0x40) != 0;
      block = (lsb & 0x20) != 0;
      if ((lsb & 0x1f) != 0) {
        throw new Decoder.DecodeException();
      }
    }

    @Override
    public String getMnemonic()
    {
      return "pull";
    }

    @Override
    public String getParamsDisplay()
    {
      return (ifEmpty ? "ifempty " : "") + (block ? "block" : "noblock");
    }
  }

  public static class Mov extends Instruction
  {
    private static final Map<Integer, Source> code2src =
      new HashMap<Integer, Source>();
    private static final Map<Integer, Destination> code2dst =
      new HashMap<Integer, Destination>();
    private static final Map<Integer, Operation> code2op =
      new HashMap<Integer, Operation>();

    private enum Source
    {
      PINS(0b000, "pins", (sm) -> sm.getAllPins()),
      X(0b001, "x", (sm) -> sm.getX()),
      Y(0b010, "y", (sm) -> sm.getY()),
      NULL(0b011, "null", (sm) -> 0),
      RESERVED_4(0b100, "???", null),
      STATUS(0b101, "status", (sm) -> sm.getStatus().asWord()),
      ISR(0b110, "isr", (sm) -> sm.getISRValue()),
      OSR(0b111, "osr", (sm) -> sm.getOSRValue());

      private final int code;
      private final String mnemonic;
      private final Function<SM, Integer> eval;

      private Source(final int code, final String mnemonic,
                     final Function<SM, Integer> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2src.put(code, this);
      }

      public Integer read(final SM sm)
      {
        return eval.apply(sm);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private enum Destination
    {
      PINS(0b000, "pins", (sm, data) -> {
          sm.setAllPins(data);
          return null;
        }),
      X(0b001, "x", (sm, data) -> {
          sm.setX(data);
          return null;
        }),
      Y(0b010, "y", (sm, data) -> {
          sm.setY(data);
          return null;
        }),
      RESERVED_3(0b011, "???", null),
      EXEC(0b100, "exec", (sm, data) -> {
          sm.insertInstruction(data);
          return null;
        }),
      PC(0b101, "pc", (sm, data) -> {
          sm.setPC(data);
          return null;
        }),
      ISR(0b110, "isr", (sm, data) -> {
          sm.setISRValue(data);
          return null;
        }),
      OSR(0b111, "osr", (sm, data) -> {
          sm.setOSRValue(data);
          return null;
        });

      private final int code;
      private final String mnemonic;
      private final BiFunction<SM, Integer, Void> eval;

      private Destination(final int code, final String mnemonic,
                          final BiFunction<SM, Integer, Void> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2dst.put(code, this);
      }

      public void write(final SM sm, final int data)
      {
        eval.apply(sm, data);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private enum Operation
    {
      NONE(0b00, "", (data) -> data),
      INVERT(0b01, "x", (data) -> ~data),
      BIT_REVERSE(0b10, "y", (data) -> Integer.reverse(data)),
      RESERVED_3(0b11, "???", null);

      private final int code;
      private final String mnemonic;
      private final Function<Integer, Integer> eval;

      private Operation(final int code, final String mnemonic,
                     final Function<Integer, Integer> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2op.put(code, this);
      }

      private int apply(final int data)
      {
        return eval.apply(data);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private Source src;
    private Destination dst;
    private Operation op;

    public Mov(final SM sm)
    {
      super(sm);

      // force class initializer to be called such that map is filled
      src = Source.PINS;
      dst = Destination.PINS;
      op = Operation.NONE;
    }

    @Override
    public ResultState execute()
    {
      dst.write(sm, op.apply(src.read(sm)));
      return
        dst == Destination.EXEC ?
        ResultState.STALL :
        (dst == Destination.PC ?
         ResultState.JUMP :
         ResultState.COMPLETE);
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      src = code2src.get(lsb & 03);
      if (src == Source.RESERVED_4) {
        throw new Decoder.DecodeException();
      }
      dst = code2dst.get((lsb & 0xe0) >>> 5);
      if (dst == Destination.RESERVED_3) {
        throw new Decoder.DecodeException();
      }
      op = code2op.get((lsb & 0x18) >>> 3);
      if (op == Operation.RESERVED_3) {
        throw new Decoder.DecodeException();
      }
    }

    @Override
    public String getMnemonic()
    {
      return "mov";
    }

    @Override
    public String getParamsDisplay()
    {
      final String strOp = op.toString();
      return dst + ", " + (!strOp.isEmpty() ? strOp + " " : "") + src;
    }
  }

  public static class Irq extends Instruction
  {
    private boolean clr;
    private boolean wait;
    private int index;

    public Irq(final SM sm)
    {
      super(sm);
    }

    @Override
    public ResultState execute()
    {
      final boolean stall;
      final int irqNum = getIRQNum(sm.getNum(), index);
      if (clr) {
        sm.clearIRQ(irqNum);
        stall = false;
      } else {
        sm.setIRQ(irqNum);
        stall = wait;
      }
      return stall ? ResultState.STALL : ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      if ((lsb & 0x80) != 0) {
        throw new Decoder.DecodeException();
      }
      clr = (lsb & 0x40) != 0;
      wait = (lsb & 0x20) != 0;
      index = lsb & 0x1f;
      checkIRQIndex(index);
    }

    @Override
    public String getMnemonic()
    {
      return "irq";
    }

    @Override
    public String getParamsDisplay()
    {
      /*
       * Note: Modes "", "set" and "nowait" are all synonyms for the
       * same thing, namely that both flags (clr, wait) are not set.
       * For display, we deliberately choose "".
       */
      final String mode = clr ? "clear" : (wait ? "wait" : "");
      return mode + " " + getIRQNumDisplay(index);
    }
  }

  public static class Set extends Instruction
  {
    private static final Map<Integer, Destination> code2dst =
      new HashMap<Integer, Destination>();

    private enum Destination
    {
      PINS(0b000, "pins", (sm, data) -> {
          sm.setAllPins(data);
          return null;
        }),
      X(0b001, "x", (sm, data) -> {
          sm.setX(data);
          return null;
        }),
      Y(0b010, "y", (sm, data) -> {
          sm.setY(data);
          return null;
        }),
      RESERVED_3(0b011, "???", null),
      PINDIRS(0b100, "pindirs", (sm, data) -> {
          sm.setPinDirs(data);
          return null;
        }),
      RESERVED_5(0b101, "???", null),
      RESERVED_6(0b110, "???", null),
      RESERVED_7(0b111, "???", null);

      private final int code;
      private final String mnemonic;
      private final BiFunction<SM, Integer, Void> eval;

      private Destination(final int code, final String mnemonic,
                          final BiFunction<SM, Integer, Void> eval)
      {
        this.code = code;
        this.mnemonic = mnemonic;
        this.eval = eval;
        code2dst.put(code, this);
      }

      public void write(final SM sm, final int data)
      {
        eval.apply(sm, data);
      }

      @Override
      public String toString()
      {
        return mnemonic;
      }
    }

    private Destination dst;
    private int data;

    public Set(final SM sm)
    {
      super(sm);

      // force class initializer to be called such that map is filled
      dst = Destination.PINS;
    }

    @Override
    public ResultState execute()
    {
      dst.write(sm, data);
      return ResultState.COMPLETE;
    }

    @Override
    public void decodeLSB(final int lsb)
      throws Decoder.DecodeException
    {
      dst = code2dst.get((lsb & 0xe0) >>> 5);
      if ((dst == Destination.RESERVED_3) ||
          (dst == Destination.RESERVED_5) ||
          (dst == Destination.RESERVED_6) ||
          (dst == Destination.RESERVED_7)) {
        throw new Decoder.DecodeException();
      }
      data = lsb & 0x1f;
    }

    @Override
    public String getMnemonic()
    {
      return "set";
    }

    @Override
    public String getParamsDisplay()
    {
      return dst + ", " + data;
    }
  }
}

/*
 * Local Variables:
 *   coding:utf-8
 *   mode:Java
 * End:
 */
