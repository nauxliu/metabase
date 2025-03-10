import React, { Component } from "react";
import PropTypes from "prop-types";

import Icon from "metabase/components/Icon";
import PopoverWithTrigger from "metabase/components/PopoverWithTrigger";
import SelectButton from "metabase/core/components/SelectButton";

import _ from "underscore";
import cx from "classnames";

import { createSelector } from "reselect";

import { color } from "metabase/lib/colors";

import Uncontrollable from "metabase/hoc/Uncontrollable";
import { composeEventHandlers } from "metabase/lib/compose-event-handlers";
import { SelectAccordionList } from "./Select.styled";

const MIN_ICON_WIDTH = 20;

class Select extends Component {
  static propTypes = {
    className: PropTypes.string,

    // one of these is required
    options: PropTypes.any,
    sections: PropTypes.any,
    children: PropTypes.any,

    value: PropTypes.any.isRequired,
    name: PropTypes.string,
    defaultValue: PropTypes.any,
    onChange: PropTypes.func.isRequired,
    multiple: PropTypes.bool,
    placeholder: PropTypes.string,
    disabled: PropTypes.bool,
    hiddenIcons: PropTypes.bool,

    // PopoverWithTrigger props
    isInitiallyOpen: PropTypes.bool,
    triggerElement: PropTypes.any,
    onClose: PropTypes.func,

    // SelectButton props
    buttonProps: PropTypes.object,
    buttonText: PropTypes.string, // will override selected options text

    // AccordianList props
    searchProp: PropTypes.string,
    searchCaseInsensitive: PropTypes.bool,
    searchPlaceholder: PropTypes.string,
    searchFuzzy: PropTypes.bool,
    hideEmptySectionsInSearch: PropTypes.bool,
    width: PropTypes.number,

    optionNameFn: PropTypes.func,
    optionValueFn: PropTypes.func,
    optionDescriptionFn: PropTypes.func,
    optionSectionFn: PropTypes.func,
    optionDisabledFn: PropTypes.func,
    optionIconFn: PropTypes.func,
    optionClassNameFn: PropTypes.func,
    optionStylesFn: PropTypes.func,
  };

  static defaultProps = {
    optionNameFn: option => option.children || option.name,
    optionValueFn: option => option.value,
    optionDescriptionFn: option => option.description,
    optionDisabledFn: option => option.disabled,
    optionIconFn: option => option.icon,
  };

  constructor(props) {
    super(props);

    // reselect selectors
    const _getValue = props =>
      // If a defaultValue is passed, replace a null value with it.
      // Otherwise, allow null values since we sometimes want them.
      Object.prototype.hasOwnProperty.call(props, "defaultValue") &&
      props.value == null
        ? props.defaultValue
        : props.value;

    const _getValues = createSelector([_getValue], value =>
      Array.isArray(value) ? value : [value],
    );
    const _getValuesSet = createSelector(
      [_getValues],
      values => new Set(values),
    );
    this._getValues = () => _getValues(this.props);
    this._getValuesSet = () => _getValuesSet(this.props);
    this.selectButtonRef = React.createRef();
  }

  _getSections() {
    // normalize `children`/`options` into same format as `sections`
    const { children, sections, options } = this.props;
    if (children) {
      const optionToItem = option => option.props;
      const first = Array.isArray(children) ? children[0] : children;
      if (first && first.type === OptionSection) {
        return React.Children.map(children, child => ({
          ...child.props,
          items: React.Children.map(child.props.children, optionToItem),
        }));
      } else if (first && first.type === Option) {
        return [{ items: React.Children.map(children, optionToItem) }];
      }
    } else if (options) {
      if (this.props.optionSectionFn) {
        return _.chain(options)
          .groupBy(this.props.optionSectionFn)
          .pairs()
          .map(([section, items]) => ({ name: section, items }))
          .value();
      } else {
        return [{ items: options }];
      }
    } else if (sections) {
      return sections;
    }
    return [];
  }

  itemIsSelected = option => {
    const optionValue = this.props.optionValueFn(option);
    return this._getValuesSet().has(optionValue);
  };

  itemIsClickable = option => !this.props.optionDisabledFn(option);

  handleChange = option => {
    const { name, multiple, onChange } = this.props;
    const optionValue = this.props.optionValueFn(option);
    let value;
    if (multiple) {
      const values = this._getValues();
      value = this.itemIsSelected(option)
        ? values.filter(value => value !== optionValue)
        : [...values, optionValue];
      value.changedItem = optionValue;
    } else {
      value = optionValue;
    }
    onChange({ target: { name, value } });
    if (!multiple) {
      this._popover.close();
      this.handleClose();
    }
  };

  renderItemIcon = item => {
    if (this.props.hiddenIcons) {
      return null;
    }

    const icon = this.props.optionIconFn(item);
    if (icon) {
      return (
        <Icon
          name={icon}
          size={item.iconSize || 18}
          color={item.iconColor || color("text-dark")}
          style={{ minWidth: MIN_ICON_WIDTH }}
        />
      );
    }

    if (this.itemIsSelected(item)) {
      return (
        <Icon
          name="check"
          size={14}
          color={color("text-dark")}
          style={{ minWidth: MIN_ICON_WIDTH }}
        />
      );
    }

    return <span style={{ minWidth: MIN_ICON_WIDTH }} />;
  };

  handleClose = () => {
    // Focusing in the next tick prevents it is from reopening
    // when closed by selecting an item with Enter
    setTimeout(() => {
      this.selectButtonRef.current?.focus();
    }, 0);
  };

  render() {
    const {
      buttonProps,
      className,
      placeholder,
      searchProp,
      searchCaseInsensitive,
      searchPlaceholder,
      searchFuzzy,
      hideEmptySectionsInSearch,
      isInitiallyOpen,
      onClose,
      disabled,
      width,
    } = this.props;

    const sections = this._getSections();
    const selectedNames = sections
      .map(section =>
        section.items.filter(this.itemIsSelected).map(this.props.optionNameFn),
      )
      .flat()
      .filter(n => n);

    return (
      <PopoverWithTrigger
        ref={ref => (this._popover = ref)}
        triggerElement={
          this.props.triggerElement || (
            <SelectButton
              ref={this.selectButtonRef}
              className="flex-full"
              hasValue={selectedNames.length > 0}
              disabled={disabled}
              {...buttonProps}
            >
              {this.props.buttonText
                ? this.props.buttonText
                : selectedNames.length > 0
                ? selectedNames.map((name, index) => (
                    <span key={index}>
                      {name}
                      {index < selectedNames.length - 1 ? ", " : ""}
                    </span>
                  ))
                : placeholder}
            </SelectButton>
          )
        }
        onClose={composeEventHandlers(onClose, this.handleClose)}
        triggerClasses={cx("flex", className)}
        isInitiallyOpen={isInitiallyOpen}
        disabled={disabled}
        verticalAttachments={["top", "bottom"]}
        // keep the popover from jumping around one its been opened,
        // this can happen when filtering items via search
        pinInitialAttachment
      >
        <SelectAccordionList
          hasInitialFocus
          sections={sections}
          className="MB-Select"
          alwaysExpanded
          width={width}
          itemIsSelected={this.itemIsSelected}
          itemIsClickable={this.itemIsClickable}
          renderItemName={this.props.optionNameFn}
          getItemClassName={this.props.optionClassNameFn}
          getItemStyles={this.props.optionStylesFn}
          renderItemDescription={this.props.optionDescriptionFn}
          renderItemIcon={this.renderItemIcon}
          onChange={this.handleChange}
          searchable={!!searchProp}
          searchProp={searchProp}
          searchCaseInsensitive={searchCaseInsensitive}
          searchFuzzy={searchFuzzy}
          searchPlaceholder={searchPlaceholder}
          hideEmptySectionsInSearch={hideEmptySectionsInSearch}
        />
      </PopoverWithTrigger>
    );
  }
}

export default Uncontrollable()(Select);
export class OptionSection extends Component {
  static propTypes = {
    name: PropTypes.any,
    icon: PropTypes.any,
    children: PropTypes.any.isRequired,
  };
  render() {
    return null;
  }
}
export class Option extends Component {
  static propTypes = {
    value: PropTypes.any.isRequired,

    // one of these two is required
    name: PropTypes.any,
    children: PropTypes.any,

    icon: PropTypes.any,
    disabled: PropTypes.bool,
  };
  render() {
    return null;
  }
}
