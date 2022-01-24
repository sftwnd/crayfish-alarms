package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import com.github.sftwnd.crayfish.common.required.RequiredFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Временной диапазон с набором элементов, промаркированных time-меткой, попадающей в границы диапазона. По запросу из диапазона
 * можно вытащить набор элементов с меткой до момента, указанного параметром запроса. Возвращаемые запросом элементы не сохраняются
 * в диапазоне.
 * @param <M> Тип элемента при добавлении
 * @param <R> Тип элемента при извлечении
 */
public class TimeRangeItems<M,R> {

    // Начало периода действия региона
    @Getter private final Instant startInstant;
    // Верхняя граница интервала (exclude...)
    @Getter private final Instant lastInstant;
    private final Instant lastDelayedInstant;
    // Размер внутреннего chunk-а деления интервала
    @Getter private final Duration interval;
    // Минимальная задержка опроса в ACTIVE статусе. Позволяет разгрузить процессор, но уменьшить точность срабатывания
    // event-а приблизительно (в среднем) до величины задержки.
    // P.S.> Для отрицательных ставится Duration.ZERO, не может быть больше размера внутреннего chunk-а: interval
    @Getter private final Duration delay;
    // Таймаут на доставку задержавшихся сообщений.
    // С момента наступления lastInstant выдерживается timeout на приход сообщений в обработку
    @Getter private final Duration completeTimeout;
    // Получение даты из регистрируемого сообщения
    private final Expectation<M,? extends TemporalAccessor> expectation;
    // Сравнение двух зарегистрированных объектов
    private final Comparator<? super M> comparator;
    // Получение результирующего элемента из зарегистрированного
    private final RequiredFunction<M,R> extractor;
    // Момент ближайшего элемента. В случае отсутствия - null
    @Setter(value = AccessLevel.PRIVATE)
    private Instant nearestInstant = null;
    // Набор элементов, распределённый по диапазонам размера interval
    // Структура хранения TreeMap, что гарантирует порядок обхода по возрастанию
    // Внутренние элементы содержатся в TreeSet, что тоже гарантирует порядок
    // Здесь мы указываем не интерфейс, а реализацию сознательно!!!
    private final TreeMap<Instant, TreeSet<M>> expectedMap = new TreeMap<>();

    /**
     *
     * Объект, содержащий M объекты на диапазон для поиска сработавших
     *
     * @param instant Момент, ограничивающий период обработки региона
     * @param duration Длительность периода региона (если отрицательный, то слева от instant, иначе - справа).
     * @param interval Интервалы на которые бьётся duration (если &gt; duration или &lt;= ZERO, то принимается равным duration.abs())
     * @param delay Интервалы проверки на срабатывание имеющихся Expected объектов
     * @param completeTimeout Через заданный интервал после завершения описываемого диапазона в случае отсутствия обрабатываемых объектов актор останавливается
     * @param expectation Получение временной метки из поступившего элемента
     * @param comparator Переопределение компаратора для упорядочивания Expected объектов не только по временному возрастанию, но и по внутреннему содержимому
     * @param extractor Метод преобразования входящего элемента в результирующий
     */
    public TimeRangeItems(
            @NonNull  Instant  instant,
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration delay,
            @NonNull  Duration completeTimeout,
            @NonNull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator,
            @NonNull  RequiredFunction<M,R> extractor
    ) {
        assert !Duration.ZERO.equals(duration);
        // ### Instant/Duration
        this.startInstant = Optional.of(duration).filter(Duration::isNegative).map(instant::plus).orElse(instant);
        this.lastInstant = Optional.of(duration).filter(Predicate.not(Duration::isNegative)).map(instant::plus).orElse(instant);
        this.interval = Optional.of(interval).filter(Predicate.not(Duration::isNegative)).filter(iv -> iv.compareTo(duration.abs()) <= 0).orElse(duration.abs());
        this.delay = ofNullable(delay)
                .filter(Predicate.not(Duration::isNegative))
                .map(d -> d.compareTo(this.interval) > 0 ? this.interval : d)
                .orElse(Duration.ZERO);
        this.lastDelayedInstant = this.lastInstant.minus(this.delay);
        this.completeTimeout = completeTimeout.abs();
        // ### Comparison
        this.expectation = expectation;
        this.comparator = comparator != null ? comparator : this::compareObjects;
        // ### Transformation
        this.extractor = extractor;
    }

    /**
     * Временной интервал с учётом completeTimeout исчерпаны на текущий момент
     * @return true если исчерпан или false в противном случае
     */
    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * Временной интервал с учётом completeTimeout исчерпаны на переданный момент
     * @param instant момент времени на который производится проверка
     * @return true если исчерпан или false в противном случае
     */
    public boolean isExpired(@Nullable Instant instant) {
        return !ofNullable(instant).orElseGet(Instant::now)
                .isBefore(this.lastInstant.plus(this.completeTimeout));
    }

    /**
     * Проверяется, что структура не содержит элементов и интервал с учётом completeTimeout исчерпан на текущий момент времени
     * @return true если исчерпан или false в противном случае
     */
    public boolean isComplete() {
        return isComplete(Instant.now());
    }

    /**
     * Проверяется, что структура не содержит элементов и интервал с учётом completeTimeout исчерпан на переданный момент времени
     * @param instant момент времени на который производится проверка
     * @return true если исчерпан или false в противном случае
     */
    public boolean isComplete(@Nullable Instant instant) {
        return this.expectedMap.isEmpty() && isExpired(instant);
    }

    /**
     * Добавляем указанный набор элементов в карту диапазонов
     * Так же будет установлен атрибут: nearestInstant
     * Данные, не попадающие в диапазон, игнорируются с выводом сообщения
     * @param elements коллекция добавляемых элементов
     * @return список проигнорированных элементов
     */
    public Collection<M> addElements(@Nonnull Collection<M> elements) {
        List<M> excludes = new ArrayList<>();
        //noinspection ConstantConditions
        elements.stream().filter(Objects::nonNull)
                // Проверяем на попадание в диапазон
                .filter(element -> checkRange(element) || !excludes.add(element))
                // Если элемент самый ранний, то отмечаем его Instant
                // P.S.> Ввиду наличия терминального оператора peek сработает для каждого элемента, прошедшего через него.
                .peek(elm -> Optional.of(instant(elm))
                        .filter(inst -> inst.isBefore(ofNullable(this.nearestInstant).orElse(Instant.MAX)))
                        .ifPresent(this::setNearestInstant))
                // Группируем по диапазонам instantKey
                .collect(
                        Collectors.groupingBy(
                                elm -> getTemporalKey(instant(elm)),
                                Collectors.toCollection(() -> new TreeSet<>(this::compare))
                        )
                ).forEach((key,value) -> ofNullable(this.expectedMap.get(key))
                        .ifPresentOrElse(
                                // Если присутствует - расширяем
                                set -> set.addAll(value)
                                // Если отсутствует - добавляем
                                , () -> this.expectedMap.put(key, value))
                );
        return excludes;
    }

    /**
     * Извлечение из сохранённых элементов тех, которые согласно временному маркеру считаются сработавшими на текущий момент
     * @return Список сработавших элементов
     */
    public @Nonnull Set<R> getFiredElements() {
        // Ищем на текущий момент
        return getFiredElements(Instant.now());
    }

    /**
     * Извлечение из сохранённых элементов тех, которые согласно временному маркеру считаются сработавшими на момент переданный параметром
     * @param instant момент времени на который производится проверка
     * @return Список сработавших элементов
     */
    public @Nonnull Set<R> getFiredElements(@Nullable Instant instant) {
        Instant now = ofNullable(instant).orElseGet(Instant::now);
        // Ключ, соответствующий текущему моменту
        Instant nowKey = getInstantKey(now);
        HashSet<R> result = new HashSet<>();
        addCollectionOnProcess(
                this.expectedMap
                        .entrySet()
                        .stream()
                        // Так как TreeMap, то порядок идет по возрастанию ключа instantKey и обрабатываем все записи у которых
                        // instantKey < nextKey
                        .takeWhile(entry -> !entry.getKey().isAfter(nowKey))
                        // Собираем все ключи в набор (чтобы можно было модифицировать первичный TreeMap
                        .map(Map.Entry::getKey).collect(Collectors.toSet())
                        // По всем ключам, которые могут содержать сработавшие элементы
                        .stream()
                        // Вызываем обработку набора, удалённого из первичной Map
                        .flatMap(key -> processKey(this.expectedMap.remove(key), now, key.isBefore(nowKey), result))
                        // Всё, что вернули обработчики собираем в set и отправляем обратно в Map
                        .collect(Collectors.toCollection(() -> new TreeSet<>(this.comparator))));
        return result;
    }

    private void addCollectionOnProcess(@Nonnull Collection<M> elements) {
        // На момент вызова были удалены строки и требуется пересчитать время ближайшего элемента (если существует)
        setNearestInstant(findNearestInstant());
        addElements(elements);
    }

    private Instant findNearestInstant() {
        return ofNullable(this.expectedMap.firstEntry())
                .map(Map.Entry::getValue)
                .map(TreeSet::first)
                .map(this::instant)
                .orElse(null);
    }

    private Stream<M> processKey(Set<M> elements, Instant now, boolean complete, Set<R> result) {
        return complete ? processComplete(elements, result) : processIncomplete(elements, now, result);
    }

    private Stream<M> processComplete(Set<M> elements, Set<R> result) {
        elements.stream().map(this.extractor).forEach(result::add);
        return Stream.empty();
    }

    private Stream<M> processIncomplete(Set<M> elements, Instant now, Set<R> result) {
        return elements
                .stream()
                .collect(Collectors.partitioningBy(element -> happened(element,now), Collectors.toSet()))
                .entrySet()
                .stream()
                .flatMap(entry -> Optional.of(entry)
                        .filter(Map.Entry::getKey)
                        .map(b -> processComplete(entry.getValue(), result))
                        .orElseGet(entry.getValue()::stream)
                );
    }

    /* >>> [DURATIONS] >>> ========================================================================================== */

    /**
     * Таймаут до момента ближайшего имеющегося Expected, но не менее, чем delay, а в случае отсутствия - до следующей
     * по времени границы - либо startInstant, либо lastInstant + completeDuration
     * @param now момент времени для которого считаем значение
     * @return таймаут до ближайшего события с учётом delay
     */
    public Duration duration(@NonNull Instant now) {
        // Если время указано до начала диапазона
        if (now.isBefore(this.startInstant)) {
            return durationToStart(now);
        // Если время начала диапазона прошло и нет элементов
        } else if (this.expectedMap.isEmpty()) {
            return durationToStop(now);
        // Если есть элементы и время попадает в диапазон
        } else if (now.isBefore(this.lastInstant)) {
            return durationToExpect(now);
        } else {
            return Duration.ZERO;
        }
    }

    /**
     * Таймаут до момента ближайшего имеющегося Expected, но не менее, чем delay, а в случае отсутствия - до следующей
     * по времени границы - либо startInstant, либо lastInstant + completeDuration
     * @return таймаут до ближайшего события с учётом delay от текущего момента
     */
    public Duration duration() {
        return duration(Instant.now());
    }

    // Время до момента после lastInstant на completeTimeout duration. Если после этого момента мы находимся в COMPLETE,
    // то актор завершается.
    // Это время дается AKKA System на доставку до нас сообщение с заказом на обработку. Дело в том, что не предполагается
    // получение заданий в обработку после момента их наступления. Данный актор принимает только сообщения на будущее
    private @Nonnull Duration durationToStop(Instant now) {
        return durationTo(this.lastInstant.plus(this.completeTimeout), now);
    }

    // Время от указанного момента до срабатывания первого элемента, а в случе отсутствия - до завершения диапазона текущего ключа
    private @Nonnull Duration durationToExpect(@Nonnull Instant now) {
        // Если определён ближайший элемент, то ждём до его момента, в противном случае - до завершения времени диапазона
        // P.S. - возможно надо ждать до следующего ключа... Вроде пока не видится оснований, но был единичный прецедент
        //        зависания - предварительно связан с использованием BalancingPool, который не поддерживается данным Актором!!!.
        //        Если будет повторяться - надо посмотреть в направлении оптимизации durationToExpect
        return Optional.of(durationTo( // Берём Delay до ближайшего элемента, а если его нет, то до конца chunk-а
                        ofNullable(this.nearestInstant)
                                .orElse(this.lastInstant),
                        now))
                // Проверяем, что он превышает delay
                .filter(d -> d.compareTo(this.delay) >= 0)
                // Если не превышает, то при попадании до lastDelayedInstant возвращаем delay, иначе - оставшееся время to lastInstant
                .orElseGet(() -> now.isAfter(this.lastInstant) ? Duration.ZERO
                        : now.isBefore(this.lastDelayedInstant) ? this.delay
                        : Duration.between(now, this.lastInstant));
    }

    // Время от указанного момента до срабатывания первого элемента, а в случе отсутствия - до начала активации диапазона
    private @Nonnull Duration durationToStart(@Nonnull Instant now) {
        return durationTo(
                ofNullable(this.nearestInstant).orElse(this.startInstant),
                now);
    }

    // Время до наступления указанного момента от момента параметра now
    private static @Nonnull Duration durationTo(@Nonnull Instant instant, @Nonnull Instant now) {
        return instant.isAfter(now)
                ? Duration.between(now, instant)
                : Duration.ZERO;
    }

    /* ========================================================================================== >>> [DURATIONS] >>> */

    private boolean checkRange(@Nonnull M element) {
        return Optional.of(element)
                .map(this::instant)
                .filter(Predicate.not(this.startInstant::isAfter))
                .filter(this.lastInstant::isAfter)
                .isPresent();
    }

    /**
     * Округляет переданный instant до начала интервала
     * @param instant Момент на который необходимо определить ключ периода опроса
     * @return момент, описывающий диапазон периода опроса
     */
    private Instant getInstantKey(@Nonnull Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli() - instant.toEpochMilli() % this.interval.toMillis());
    }

    private Instant getTemporalKey(@Nullable TemporalAccessor temporalAccessor) {
        return getInstantKey(Instant.from(ofNullable(temporalAccessor).orElse(Instant.MIN)));
    }

    /**
        Данная функция сравнивает два имеющихся объекта. Если их моменты срабатывания не совпадают, то результат сравнения
        совпадает с результатом сравнения моментов времени срабатывания объектов. В противном случае - порядок берётся по
        результату сравнения обоих объектов зарегистрированным comparator-ом
     */
    private int compare(@Nonnull M first, @Nonnull M second) {
        return first == second ? 0
             : Optional.of(instant(first).compareTo(instant(second)))
                .filter(result -> result != 0)
                .orElseGet(() -> comparator.compare(first, second));

    }

    private int compareObjects(@Nonnull Object first, @Nonnull Object second) {
        return Integer.compare(first.hashCode(), second.hashCode());
    }

    private boolean happened(@Nonnull M element, @NonNull Instant now) {
        return !instant(element).isAfter(now);
    }

    private @Nonnull Instant instant(@Nonnull M element) {
        return Instant.from(expectation.apply(element));
    }

    /**
     * Создание TimeRangeItems с ExpectedPackage как тип регистрируемых элементов
     *
     * @param instant Момент, ограничивающий период обработки региона
     * @param duration Длительность периода региона (если отрицательный, то слева от instant, иначе - справа)
     * @param interval Интервалы на которые бьётся duration (если &gt; duration или &lt;= ZERO, то принимается равным duration.abs())
     * @param delay Интервалы проверки на срабатывание имеющихся Expected объектов
     * @param completeTimeout Через заданный интервал после завершения описываемого диапазона в случае отсутствия обрабатываемых объектов актор останавливается
     * @param comparator Переопределение компаратора для упорядочивания Expected объектов не только по временному возрастанию, но и по внутреннему содержимому
     * @param <M> тип выдаваемого элемента
     * @param <C> тип входящего элемента
     * @return экземпляр TimeRangeItems
     */
    public static <M, C extends ExpectedPackage<M,? extends TemporalAccessor>> TimeRangeItems<C,M> constructPackable(
            @NonNull  Instant  instant,
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration delay,
            @NonNull  Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return new TimeRangeItems<>(
                instant,
                duration,
                interval,
                delay,
                completeTimeout,
                ExpectedPackage::getTick,
                comparator == null ? null : (left, right) -> comparator.compare(left.getElement(), right.getElement()),
                ExpectedPackage::getElement
        );
    }

    /**
     * Создание TimeRangeItems с Expected как тип регистрируемых элементов
     *
     * @param instant Момент, ограничивающий период обработки региона
     * @param duration Длительность периода региона (если отрицательный, то слева от instant, иначе - справа)
     * @param interval Интервалы на которые бьётся duration (если &gt; duration или &lt;= ZERO, то принимается равным duration.abs())
     * @param delay Интервалы проверки на срабатывание имеющихся Expected объектов
     * @param completeTimeout Через заданный интервал после завершения описываемого диапазона в случае отсутствия обрабатываемых объектов актор останавливается
     * @param comparator Переопределение компаратора для упорядочивания Expected объектов не только по временному возрастанию, но и по внутреннему содержимому
     * @param <M> тип регистрируемого выдаваемого элемента
     * @return экземпляр TimeRangeItems
     */
    public static <M extends Expected<? extends TemporalAccessor>> TimeRangeItems<M,M> constructExpected(
            @NonNull  Instant  instant,
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration delay,
            @NonNull  Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return new TimeRangeItems<>(
                instant,
                duration,
                interval,
                delay,
                completeTimeout,
                Expected::getTick,
                comparator,
                RequiredFunction.identity()
        );
    }

}
