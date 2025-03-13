/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.changelog.entry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.UnaryOperator;

import static java.util.stream.Collectors.joining;

abstract class AbstractCompositeChangeLogEntry extends ChangeLogEntry
        implements List<ChangeLogEntry>, CompositeChangeLogEntry {
    private final List<ChangeLogEntry> children = new ArrayList<>();

    public AbstractCompositeChangeLogEntry(String header, String message) {
        super(header, message);
    }

    @Override
    public String toString() {
        return header + " " + message + "\n\n"
                + getChildren().stream().map(ChangeLogEntry::toString).collect(joining(""));
    }

    @Override
    public int size() {
        return children.size();
    }

    @Override
    public boolean isEmpty() {
        return children.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return children.contains(o);
    }

    @Override
    public Iterator<ChangeLogEntry> iterator() {
        return children.iterator();
    }

    @Override
    public Object[] toArray() {
        return children.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return children.toArray(a);
    }

    @Override
    public boolean add(ChangeLogEntry changeLogEntry) {
        return children.add(changeLogEntry);
    }

    @Override
    public boolean remove(Object o) {
        return children.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return children.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends ChangeLogEntry> c) {
        return children.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends ChangeLogEntry> c) {
        return children.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return children.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return children.retainAll(c);
    }

    @Override
    public void replaceAll(UnaryOperator<ChangeLogEntry> operator) {
        children.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super ChangeLogEntry> c) {
        children.sort(c);
    }

    @Override
    public void clear() {
        children.clear();
    }

    @Override
    public boolean equals(Object o) {
        return children.equals(o);
    }

    @Override
    public int hashCode() {
        return children.hashCode();
    }

    @Override
    public ChangeLogEntry get(int index) {
        return children.get(index);
    }

    @Override
    public ChangeLogEntry set(int index, ChangeLogEntry element) {
        return children.set(index, element);
    }

    @Override
    public void add(int index, ChangeLogEntry element) {
        children.add(index, element);
    }

    @Override
    public ChangeLogEntry remove(int index) {
        return children.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return children.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return children.lastIndexOf(o);
    }

    @Override
    public ListIterator<ChangeLogEntry> listIterator() {
        return children.listIterator();
    }

    @Override
    public ListIterator<ChangeLogEntry> listIterator(int index) {
        return children.listIterator(index);
    }

    @Override
    public List<ChangeLogEntry> subList(int fromIndex, int toIndex) {
        return children.subList(fromIndex, toIndex);
    }

    @Override
    public Spliterator<ChangeLogEntry> spliterator() {
        return children.spliterator();
    }

    @Override
    public List<ChangeLogEntry> getChildren() {
        return children;
    }
}
